# server.py
# WebSocket 서버 및 클라이언트 관리

import json
from typing import Dict, Set
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
import asyncio

from motor import bootstrap, create_pwm, cleanup_all, pump_off
from control import control_queue, motor_loop, pump_loop, dispatch_loop, set_cmd

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])

# 클라이언트 관리
# android_ctrl: 제어 앱
# android_rc: 원격 카메라 앱
clients_by_type: Dict[str, Set[WebSocket]] = {"android_ctrl": set(), "android_rc": set()}
clients_by_id: Dict[str, WebSocket] = {}

async def safe_send(ws: WebSocket, text: str):
    try: await ws.send_text(text)
    except: 
        for s in clients_by_type.values(): s.discard(ws)
        for k,v in list(clients_by_id.items()):
            if v is ws: clients_by_id.pop(k,None)

async def broadcast_to_type(t:str,payload:dict):
    text=json.dumps(payload,ensure_ascii=False)
    for ws in list(clients_by_type.get(t,set())): await safe_send(ws,text)

async def send_to_id(target_id:str,payload:dict):
    text=json.dumps(payload,ensure_ascii=False)
    ws=clients_by_id.get(target_id)
    if ws: await safe_send(ws,text)

# android_ctrl -> android_rc 전달
@app.websocket("/ws")
async def ws_endpoint(ws:WebSocket):
    await ws.accept()
    q=ws.query_params; ctype=q.get("type","unknown"); cid=q.get("id")
    clients_by_type.setdefault(ctype,set()).add(ws)
    if cid: clients_by_id[cid]=ws
    print(f"[WS] connected: type={ctype}, id={cid}")
    await safe_send(ws,json.dumps({"type":"status","content":"ready"}))

    try:
        while True:
            msg=await ws.receive_text()
            print(f"[WS] recv {ctype}({cid}): {msg}")
            await control_queue.put(msg)
            try: data=json.loads(msg)
            except: data={"Type":"RAW","Value":msg}
            if ctype=="android_ctrl":
                target_id=data.get("to")
                if target_id: await send_to_id(target_id,data)
                else: await broadcast_to_type("android_rc",data)
            elif ctype=="android_rc":
                target_id=data.get("to")
                if target_id: await send_to_id(target_id,data)
                else: await broadcast_to_type("android_ctrl",data)
    except WebSocketDisconnect:
        print(f"[WS] disconnected: type={ctype}, id={cid}")
    finally:
        clients_by_type.get(ctype,set()).discard(ws)
        if cid and clients_by_id.get(cid) is ws: clients_by_id.pop(cid,None)

# lifespan
@asynccontextmanager
async def lifespan(app:FastAPI):
    bootstrap(); create_pwm()
    set_cmd(None); pump_off()
    tasks=[asyncio.create_task(motor_loop()),asyncio.create_task(pump_loop()),asyncio.create_task(dispatch_loop())]
    app.state._tasks=tasks
    try: yield
    finally:
        for t in tasks: t.cancel()
        cleanup_all()

app.router.lifespan_context=lifespan
