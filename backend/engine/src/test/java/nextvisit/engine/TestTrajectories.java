package nextvisit.engine;

import java.util.ArrayList;
import java.util.List;

/** "2 2 _ 3 3" → 주차 1,2,4,5 의 CONFIRMED 관찰. '_'는 결측 주. 'c3' 처럼 c 접두면 CARRIED. */
final class TestTrajectories {

    private TestTrajectories() {}

    static List<Observation> parse(String spec) {
        List<Observation> out = new ArrayList<>();
        String[] tokens = spec.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if (t.equals("_")) {
                continue;
            }
            Source source = Source.CONFIRMED;
            if (t.startsWith("c")) {
                source = Source.CARRIED;
                t = t.substring(1);
            }
            out.add(new Observation(i + 1, Integer.parseInt(t), source));
        }
        return out;
    }
}
