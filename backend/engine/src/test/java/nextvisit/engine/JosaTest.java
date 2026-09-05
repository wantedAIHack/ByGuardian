package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JosaTest {

    @Test
    void eunNeun() {
        assertEquals("식사는", Josa.eunNeun("식사"));
        assertEquals("화장실 이용은", Josa.eunNeun("화장실 이용"));
        assertEquals("집 안에서 걷기는", Josa.eunNeun("집 안에서 걷기"));
    }

    @Test
    void euroRo() {
        assertEquals("지팡이로", Josa.euroRo("지팡이"));
        assertEquals("워커로", Josa.euroRo("워커"));
        assertEquals("혼자 하심으로", Josa.euroRo("혼자 하심"));
        assertEquals("좋은 날만으로", Josa.euroRo("좋은 날만"));
    }

    @Test
    void euroRoWithRieulFinalUsesRoNotEuro() {
        // 받침이 ㄹ이면 "으로"가 아니라 "로". 카탈로그 단어 중엔 이 분기를 타는 게 없어 별도로 검증한다
        assertEquals("서울로", Josa.euroRo("서울"));
    }

    @Test
    void nonHangulEndingTreatedAsNoFinal() {
        assertEquals("level 3는", Josa.eunNeun("level 3"));
    }
}
