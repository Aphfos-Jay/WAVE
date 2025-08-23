# 안드로이드 -> 서버 -> RPi 연결 및 조이스틱으로 컨트롤
# 펌프 버튼을 통해 펌프 분사 가능

import asyncio, json, time, signal, sys, websockets
import RPi.GPIO as GPIO

# ----- 모터 init -----

# M1: 좌/우
# M2: 앞/뒤
M1_ENA, M1_IN1, M1_IN2 = 12, 23, 24
M2_ENB, M2_IN3, M2_IN4 = 13, 25, 8

SPEED_STEER = 100
SPEED_DRIVE = 100

# 0.1초마다 명령어 받음
# 1.5초동안 명령어 없으면 정지
TICK_SEC     = 0.1
STOP_TIMEOUT = 1.5

# ws://192.168.137.1:8080/ws/agent/rpi
SERVER_IP = "192.168.137.1"
PORT      = 8080
CLIENT_ID = "rpi"
WS_URI    = f"ws://{SERVER_IP}:{PORT}/ws/agent/{CLIENT_ID}"

# ----- 펌프 init -----
PUMP_PIN = 17
RELAY_ACTIVE_HIGH = True
PUMP_ON_LEVEL  = GPIO.HIGH if RELAY_ACTIVE_HIGH else GPIO.LOW
PUMP_OFF_LEVEL = GPIO.LOW  if RELAY_ACTIVE_HIGH else GPIO.HIGH

# 0.05초마다 명령어 받음
# 0.5초동안 명령어 없으면 정지
HB_TICK_SEC  = 0.05
HOSE_TIMEOUT = 0.5

# ----- GPIO 초기화 -----
GPIO.setmode(GPIO.BCM)
GPIO.setup([M1_ENA, M1_IN1, M1_IN2, M2_ENB, M2_IN3, M2_IN4], GPIO.OUT)
GPIO.setup(PUMP_PIN, GPIO.OUT, initial=PUMP_OFF_LEVEL)

pwm_m1 = GPIO.PWM(M1_ENA, 1000); pwm_m1.start(0)
pwm_m2 = GPIO.PWM(M2_ENB, 1000); pwm_m2.start(0)

def _clip(x): return max(0, min(100, x))

# M1: 좌/우
# M1 전진: 조향 좌, M1 후진: 조향 우
def m1_left(duty=SPEED_STEER):
    GPIO.output(M1_IN1, GPIO.HIGH); GPIO.output(M1_IN2, GPIO.LOW)
    pwm_m1.ChangeDutyCycle(_clip(duty))

def m1_right(duty=SPEED_STEER):
    GPIO.output(M1_IN1, GPIO.LOW); GPIO.output(M1_IN2, GPIO.HIGH)
    pwm_m1.ChangeDutyCycle(_clip(duty))

def m1_stop():
    GPIO.output(M1_IN1, GPIO.LOW); GPIO.output(M1_IN2, GPIO.LOW)
    pwm_m1.ChangeDutyCycle(0)

# M2: 전/후진
def m2_forward(duty=SPEED_DRIVE):
    GPIO.output(M2_IN3, GPIO.HIGH); GPIO.output(M2_IN4, GPIO.LOW)
    pwm_m2.ChangeDutyCycle(_clip(duty))

def m2_backward(duty=SPEED_DRIVE):
    GPIO.output(M2_IN3, GPIO.LOW); GPIO.output(M2_IN4, GPIO.HIGH)
    pwm_m2.ChangeDutyCycle(_clip(duty))

def m2_stop():
    GPIO.output(M2_IN3, GPIO.LOW); GPIO.output(M2_IN4, GPIO.LOW)
    pwm_m2.ChangeDutyCycle(0)

# 전체 정지
def stop_all():
    m1_stop(); m2_stop()

# ----- 펌프 제어 -----                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            
hose_active = False
last_hose_time = 0.0

def pump_on():
    global hose_active
    GPIO.output(PUMP_PIN, PUMP_ON_LEVEL)
    if not hose_active:
        print("[PUMP] ON")
    hose_active = True

def pump_off():
    global hose_active
    GPIO.output(PUMP_PIN, PUMP_OFF_LEVEL)
    if hose_active:
        print("[PUMP] OFF")
    hose_active = False

# 문자열 검사
def mark_hose():
    global last_hose_time
    last_hose_time = time.time()
    if not hose_active:
        pump_on()

def set_button(button_type: str, content: str):
    global hose_active
    if (button_type == "button") and (str(content).lower() == "hose_spray"):
        print(f"[PUMP/BUTTON] hose_spray")
        mark_hose()

# ====== 모터 명령 상태 ======
last_cmd = None
last_cmd_time = 0.0

def set_cmd(cmd: str):
    global last_cmd, last_cmd_time
    last_cmd = (cmd or "").lower() if cmd else None
    last_cmd_time = time.time()
    print(f"[MOTOR/CMD] {last_cmd}")

# 모터 명령어 매핑
# up, down, left, right,
# up_left, up_right, down_left, down_right
def apply_command(cmd: str):
    if not cmd:
        stop_all(); return
    c = cmd.lower()

    # 앞 / 뒤
    if c in ("up", "w"):
        m1_stop(); m2_forward(); return
    if c in ("down", "s"):
        m1_stop(); m2_backward(); return

    # 좌 / 우
    if c in ("left", "a"):
        m2_stop(); m1_left(); return
    if c in ("right", "d"):
        m2_stop(); m1_right(); return

    # 대각
    if c == "up_left":
        m1_left(); m2_forward(); return
    if c == "up_right":
        m1_right(); m2_forward(); return
    if c == "down_left":
        m1_left(); m2_backward(); return
    if c == "down_right":
        m1_right(); m2_backward(); return

    # 이외 명령어 정지
    stop_all()

# 모터, 펌프 loop
async def motor_control_loop():
    while True:
        if time.time() - last_cmd_time > STOP_TIMEOUT:
            if last_cmd is not None:
                print("[MOTOR] timeout → STOP")
            apply_command(None)
        else:
            apply_command(last_cmd)
        await asyncio.sleep(TICK_SEC)

async def pump_heartbeat_loop():
    while True:
        if hose_active and last_hose_time > 0 and (time.time() - last_hose_time > HOSE_TIMEOUT):
            pump_off()
        await asyncio.sleep(HB_TICK_SEC)

# websocket loop
async def ws_loop():
    backoff = 1
    while True:
        try:
            print(f"[WS] connect {WS_URI}")
            async with websockets.connect(WS_URI, ping_interval=20, ping_timeout=20, max_size=None) as ws:
                print("[WS] connected")
                await ws.send(json.dumps({"type":"status","content":"rpi ready (motor+pump)"}))
                backoff = 1
                async for raw in ws:
                    try:
                        data = json.loads(raw)
                        if isinstance(data, dict) and ("type" in data or "content" in data):
                            set_button(str(data.get("type", "")), str(data.get("content", "")))

                        if isinstance(data, dict):
                            if data.get("type") == "rc" and "command" in data:
                                set_cmd(data["command"])
                            elif "command" in data:
                                set_cmd(str(data["command"]))
                            elif "content" in data:
                                v = str(data["content"])
                                if v.lower() != "hose_spray":
                                    set_cmd(v)
                        else:
                            pass
                    except json.JSONDecodeError:
                        s = str(raw).strip()
                        if s and (s.lower() != "hose_spray"):
                            set_cmd(s)
        except Exception as e:
            print(f"[WS] error: {e} → {backoff}s retry")
            pump_off()
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, 10)

# 종료 시 모두 멈춤
def cleanup_and_exit(*_):
    try:
        stop_all()
        pump_off()
        pwm_m1.stop(); pwm_m2.stop()
        GPIO.cleanup()
    finally:
        print("GPIO 정리 완료"); sys.exit(0)

signal.signal(signal.SIGINT, cleanup_and_exit)
signal.signal(signal.SIGTERM, cleanup_and_exit)

# 메인 루프
async def main():
    set_cmd(None)
    pump_off()
    await asyncio.gather(
        ws_loop(),
        motor_control_loop(),
        pump_heartbeat_loop(),
    )

if __name__ == "__main__":
    try:
        asyncio.run(main())
    except SystemExit:
        pass
    except:
        cleanup_and_exit()
