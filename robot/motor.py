# motor.py
import RPi.GPIO as GPIO
import time, atexit

# 핀 설정
M1_ENA, M1_IN1, M1_IN2 = 12, 23, 24
M2_ENB, M2_IN3, M2_IN4 = 13, 25, 8
PUMP_PIN = 17

SPEED_STEER = 100
SPEED_DRIVE = 100
RELAY_ACTIVE_HIGH = True

PUMP_ON_LEVEL  = GPIO.HIGH if RELAY_ACTIVE_HIGH else GPIO.LOW
PUMP_OFF_LEVEL = GPIO.LOW  if RELAY_ACTIVE_HIGH else GPIO.HIGH

# PWM 핸들
pwm_m1 = None
pwm_m2 = None
hose_active = False

# 초기화
def bootstrap():
    GPIO.setwarnings(False)
    try: GPIO.cleanup()
    except: pass
    time.sleep(0.05)
    GPIO.setmode(GPIO.BCM)
    GPIO.setup([M1_ENA, M1_IN1, M1_IN2, M2_ENB, M2_IN3, M2_IN4], GPIO.OUT)
    GPIO.setup(PUMP_PIN, GPIO.OUT, initial=PUMP_OFF_LEVEL)

def create_pwm():
    global pwm_m1, pwm_m2
    pwm_m1 = GPIO.PWM(M1_ENA, 1000); pwm_m1.start(0)
    pwm_m2 = GPIO.PWM(M2_ENB, 1000); pwm_m2.start(0)

def stop_pwm():
    global pwm_m1, pwm_m2
    try:
        if pwm_m1: pwm_m1.stop()
        if pwm_m2: pwm_m2.stop()
    except: pass
    pwm_m1 = pwm_m2 = None

def cleanup_all():
    stop_all(); pump_off()
    stop_pwm()
    try: GPIO.cleanup()
    except: pass
    print("[GPIO] cleanup done")

atexit.register(cleanup_all)

# 모터 제어
def _clip(x): return max(0, min(100, x))

def m1_left(duty=SPEED_STEER):
    GPIO.output(M1_IN1, GPIO.HIGH); GPIO.output(M1_IN2, GPIO.LOW)
    if pwm_m1: pwm_m1.ChangeDutyCycle(_clip(duty))

def m1_right(duty=SPEED_STEER):
    GPIO.output(M1_IN1, GPIO.LOW); GPIO.output(M1_IN2, GPIO.HIGH)
    if pwm_m1: pwm_m1.ChangeDutyCycle(_clip(duty))

def m1_stop():
    GPIO.output(M1_IN1, GPIO.LOW); GPIO.output(M1_IN2, GPIO.LOW)
    if pwm_m1: pwm_m1.ChangeDutyCycle(0)

def m2_forward(duty=SPEED_DRIVE):
    GPIO.output(M2_IN3, GPIO.HIGH); GPIO.output(M2_IN4, GPIO.LOW)
    if pwm_m2: pwm_m2.ChangeDutyCycle(_clip(duty))

def m2_backward(duty=SPEED_DRIVE):
    GPIO.output(M2_IN3, GPIO.LOW); GPIO.output(M2_IN4, GPIO.HIGH)
    if pwm_m2: pwm_m2.ChangeDutyCycle(_clip(duty))

def m2_stop():
    GPIO.output(M2_IN3, GPIO.LOW); GPIO.output(M2_IN4, GPIO.LOW)
    if pwm_m2: pwm_m2.ChangeDutyCycle(0)

def stop_all():
    m1_stop(); m2_stop()

# 펌프 제어
def pump_on():
    global hose_active
    GPIO.output(PUMP_PIN, PUMP_ON_LEVEL)
    if not hose_active: print("[PUMP] ON")
    hose_active = True

def pump_off():
    global hose_active
    GPIO.output(PUMP_PIN, PUMP_OFF_LEVEL)
    if hose_active: print("[PUMP] OFF")
    hose_active = False

def pump_is_active(): return hose_active
