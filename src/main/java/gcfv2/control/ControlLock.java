package gcfv2.control;

import java.util.concurrent.locks.ReentrantLock;

/**
 * ControlLock
 * - 안드로이드 입력 vs 음성 매크로 실행의 충돌을 방지하는 락 관리 클래스
 * - Python real_main.py 의 ControlLock 을 Java로 이식
 * - owner: 현재 락을 소유한 주체 ("android" 또는 "voice")
 * - priority: 우선순위 (숫자가 클수록 우선권이 강함)
 * - until: 락 만료 시각 (epoch millis)
 */
public class ControlLock {

    private String owner = null;
    private int priority = -1;
    private long until = 0;
    private final ReentrantLock lock = new ReentrantLock();

    /** 락 획득 */
    public boolean acquire(String owner, double ttlSec, int priority) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (this.owner == null || now >= until || priority > this.priority || this.owner.equals(owner)) {
                this.owner = owner;
                this.priority = priority;
                this.until = now + (long)(ttlSec * 1000);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 락 갱신 */
    public boolean renew(String owner, double ttlSec) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (this.owner == null || now >= until || this.owner.equals(owner)) {
                this.owner = owner;
                this.until = now + (long)(ttlSec * 1000);
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 락 해제 */
    public void release(String owner) {
        lock.lock();
        try {
            long now = System.currentTimeMillis();
            if (this.owner == null || now >= until || this.owner.equals(owner)) {
                this.owner = null;
                this.priority = -1;
                this.until = 0;
            }
        } finally {
            lock.unlock();
        }
    }

    /** 현재 락 소유자 반환 */
    public String getOwner() {
        return owner;
    }
}
