package nextvisit.engine;

/** README §7. 괄호는 스펙의 절 번호. */
public enum DetectionType {
    RISE_VS_STALL,      // (a)
    RISE_VS_DECLINE,    // (b)
    STALL_WITH_PAIN,    // (c) 참여 정체 + 통증 신호 반복
    RISE_WITH_PAIN,     // (c) 참여 상승 + 통증 신호 반복
    DECLINE_NO_SIGNAL,  // (c) 참여 감소 + 신호 없음
    FLUCTUATION,        // (d)
    TIME_OF_DAY,        // (e) 1단계
    AID_CHANGE,         // (f)
    HAND_DISUSE,        // (g)
    CONSISTENCY_DROP    // (h)
}
