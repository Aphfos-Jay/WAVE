# control.py
# 모터 및 펌프 제어, 상태 관리

import time, json, asyncio
from motor import (
    stop_all, m1_left, m1_right, m1_stop,
    m2_forward, m2_backward, m2_stop,
    pump_on, pump_off, pump_is_active
)

# 상태값
last_cmd: str|None = None   # 현재 명령
last_cmd_time: float = 0.0  # 마지막 명령 수신 시간
last_hose_time: float = 0.0 # 마지막 펌프 명령 수신 시간
control_queue: asyncio.Queue[str] = asyncio.Queue() # 제어 메시지 큐

# 명령 및 시간 갱신
def set_cmd(cmd: str|None):
    global last_cmd, last_cmd_time
    last_cmd = (cmd or "").lower() if cmd else None
    last_cmd_time = time.time()
    print(f"[MOTOR/CMD] {last_cmd}")

# 펌프 활성화 및 시간 갱신
def mark_hose():
    global last_hose_time
    last_hose_time = time.time()
    if not pump_is_active():
        pump_on()

# 모터 명령 적용
def apply_command(cmd: str|None):
    if not cmd: stop_all(); return
    c = cmd.lower()
    if c in ("forward","up", "w"):          m1_stop(); m2_forward();  return
    if c in ("back", "down", "s"):          m1_stop(); m2_backward(); return
    if c in ("left","a"):                   m2_stop();  m1_left();    return
    if c in ("right","d"):                  m2_stop();  m1_right();   return
    if c in ("forward-left", "up_left"):    m1_left();  m2_forward(); return
    if c in ("forward-right", "up_right"):  m1_right(); m2_forward(); return
    if c in ("back-left", "down_left"):     m1_left();  m2_backward();return
    if c in ("back-right", "down_right"):   m1_right(); m2_backward();return
    stop_all()

# 수신 JSON 메시지 처리
def handle_json(raw: str):
    try: data = json.loads(raw)
    except: return
    t = data.get("Type"); 
    v = str(data.get("Value","")).lower()
    if t=="Con":
        if(v in ("stop","none","")): 
            set_cmd(None)
        else:
            set_cmd(v)
    elif t=="Jet":
        print(f"[CONTROL] Jet={v}")
        pump_on() if v=="launch" else pump_off()

# 정지 변수 및 함수
TICK_SEC=0.1; STOP_TIMEOUT=1.0; HOSE_TIMEOUT=0.4; HB_TICK_SEC=0.05

# STOP_TIMEOUT 초 이상 모터 명령 없으면 모터 정지
# TICK_SEC 초마다 확인
async def motor_loop():
    global last_cmd
    while True:
        if time.time()-last_cmd_time > STOP_TIMEOUT:
            if last_cmd: print("[MOTOR] timeout→STOP")
            stop_all()
        else:
            apply_command(last_cmd)
        await asyncio.sleep(TICK_SEC)

# HOSE_TIMEOUT 초 이상 펌프 명령 없으면 펌프 정지
# HB_TICK_SEC 초마다 확인
async def pump_loop():
    while True:
        if pump_is_active() and last_hose_time>0 and (time.time()-last_hose_time>HOSE_TIMEOUT):
            pump_off()
        await asyncio.sleep(HB_TICK_SEC)

# 메시지 수신 및 처리
async def dispatch_loop():
    while True:
        raw = await control_queue.get()
        handle_json(raw)
