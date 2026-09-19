package nextvisit.api.catalog;

import java.util.*;
import nextvisit.api.common.ValidationException;
import nextvisit.api.snapshots.SnapshotBody;

/** Version 2 is an immutable vocabulary: historical answers retain these labels. No scoring or diagnosis. */
public final class QuestionnaireCatalog {
    private QuestionnaireCatalog() {}
    public static final int VERSION = 2;
    public record Questionnaire(int version, List<Question> questions) {}
    public record Question(String code, String label, String help, String kind, boolean required,
                           List<CatalogDto.CodeLabel> options, When when, List<String> exclusive) {}
    public record When(String question, List<String> anyOf) {}

    private static List<CatalogDto.CodeLabel> options(String... pairs) {
        List<CatalogDto.CodeLabel> out = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) out.add(new CatalogDto.CodeLabel(pairs[i], pairs[i + 1]));
        return List.copyOf(out);
    }
    private static When when(String question, String... values) { return new When(question, List.of(values)); }
    private static Question single(String code, String label, List<CatalogDto.CodeLabel> values, When when) {
        return new Question(code, label, null, "single", false, values, when, List.of());
    }
    private static Question multiple(String code, String label, List<CatalogDto.CodeLabel> values, When when) {
        return new Question(code, label, "해당하는 내용을 모두 골라주세요.", "multiple", false, values, when,
            values.stream().map(CatalogDto.CodeLabel::code).filter(c -> c.equals("unknown") || c.equals("none") || c.equals("not_performed")).toList());
    }
    private static Question assistance(String code, String label, String help, When when, String... ranks) {
        List<CatalogDto.CodeLabel> out = new ArrayList<>();
        for (int i = 0; i < ranks.length; i++) out.add(new CatalogDto.CodeLabel(Integer.toString(4 - i), ranks[i]));
        out.addAll(options("unknown", "직접 보지 못함", "not_performed", "이번 주 하지 않음"));
        return new Question(code, label, help, "single", true, List.copyOf(out), when, List.of());
    }
    private static Question variation(When when) {
        return single("variation", "이번 주에 필요한 도움의 양이 달라진 때가 있었나요?", options(
            "stable", "대체로 같았음", "more", "도움이 더 필요한 때가 있었음", "less", "도움이 덜 필요한 때가 있었음",
            "both", "더 필요한 때와 덜 필요한 때가 모두 있었음", "unknown", "직접 보지 못함"), when);
    }
    private static final List<CatalogDto.CodeLabel> YES_NO = options("yes", "있었음", "no", "없었음", "unknown", "직접 보지 못함");
    private static final Map<String, Questionnaire> CATALOG = create();
    private static Map<String, Questionnaire> create() {
        Map<String, Questionnaire> out = new LinkedHashMap<>();
        out.put("toilet", new Questionnaire(VERSION, List.of(
            assistance("transfer", "변기에 앉고 일어설 때 어느 정도 도움이 필요했나요?", "화장실까지 이동하는 도움은 제외해주세요.", null,
                "혼자 앉고 일어섬", "지켜보기나 말 안내 후 혼자 앉고 일어섬", "본인이 대부분 하고 일부 동작에 직접 도움", "보호자가 대부분 돕고 본인이 일부 동작", "보호자가 전부 도움"),
            assistance("clothing", "용변 전후 옷을 내리고 올릴 때 어느 정도 도움이 필요했나요?", null, null,
                "혼자 옷을 내리고 올림", "준비나 말 안내 후 혼자 옷을 정리함", "본인이 대부분 하고 일부 동작에 직접 도움", "보호자가 대부분 돕고 본인이 일부 동작", "보호자가 전부 도움"),
            assistance("hygiene", "용변 후 닦거나 씻을 때 어느 정도 도움이 필요했나요?", null, null,
                "준비부터 마무리까지 혼자 함", "물품 준비나 말 안내 후 혼자 함", "본인이 대부분 하고 일부 동작에 직접 도움", "보호자가 대부분 돕고 본인이 일부 동작", "보호자가 전부 도움"),
            single("support", "용변 후 닦거나 씻는 동안 몸을 지탱하는 도움이 있었나요?", YES_NO, when("hygiene", "0", "1", "2", "3", "4")),
            multiple("method", "이번 주 어떤 방법으로 용변을 보셨나요?", options("toilet", "일반 변기", "commode", "이동식 변기", "urinal", "소변기", "diaper", "기저귀", "other", "기타", "unknown", "직접 보지 못함"), null),
            multiple("aids", "화장실에서 사용한 보조 물품이 있나요?", options("grab_bar", "안전 손잡이", "raised_seat", "변기 높임", "walking_aid", "보행 보조 도구", "none", "없음", "other", "기타", "unknown", "직접 보지 못함"), null),
            multiple("management", "이번 주 소변이나 대변을 볼 수 있도록 별도로 관리한 것이 있나요?", options("catheter", "도뇨관(소변줄)", "enema", "관장", "none", "없음", "other", "기타", "unknown", "직접 보지 못함"), null),
            single("night", "밤에 용변을 보신 적이 있었나요?", YES_NO, null),
            single("night_help", "밤에 용변을 보실 때 어떤 도움이 있었나요?", options("no", "도움 없이 혼자 함", "guidance", "말로 안내함", "physical", "몸을 직접 도와드림", "unknown", "직접 보지 못함"), when("night", "yes")),
            variation(null))));
        out.put("dressing", new Questionnaire(VERSION, List.of(
            assistance("assistance", "옷을 입고 벗을 때 어느 정도 도움이 필요했나요?", null, null,
                "옷 준비부터 입고 벗기까지 혼자 함", "옷을 꺼내놓거나 말로 안내하면 혼자 입고 벗음", "본인이 대부분 하고 일부 동작에 직접 도움", "보호자가 대부분 돕고 본인이 일부 동작", "보호자가 전부 도움"),
            multiple("parts", "어떤 부분에서 직접 도움이 필요했나요?", options("upper", "상의", "lower", "하의", "socks", "양말", "shoes", "신발", "fasteners", "단추·지퍼", "unknown", "직접 보지 못함"), when("assistance", "0", "1", "2")), variation(when("assistance", "0", "1", "2", "3", "4")))));
        out.put("grooming", new Questionnaire(VERSION, List.of(
            assistance("washing", "세수할 때 어느 정도 도움이 필요했나요?", null, null,
                "준비부터 마무리까지 혼자 함", "물품 준비나 지켜보기·말 안내가 필요함", "일부 동작에 직접 도움", "대부분의 동작에 직접 도움", "보호자가 전부 도움"),
            assistance("brushing", "양치할 때 어느 정도 도움이 필요했나요?", null, null,
                "준비부터 마무리까지 혼자 함", "물품 준비나 지켜보기·말 안내가 필요함", "일부 동작에 직접 도움", "대부분의 동작에 직접 도움", "보호자가 전부 도움"),
            multiple("risks", "세수나 양치 중 다음과 같은 일이 있었나요?", options("body_unsteady", "몸이 흔들려 급히 붙잡음", "fall", "넘어지거나 주저앉음", "cough", "양치·헹굼 중 기침이나 사레", "other", "기타", "none", "없었음", "unknown", "직접 보지 못함"), null))));
        out.put("bathing", new Questionnaire(VERSION, List.of(
            assistance("assistance", "목욕할 때 어느 정도 참여하셨나요?", null, null,
                "머리 감기·헹구기·닦기 등을 스스로 수행",
                "준비 과정이나 안전을 위한 감시하에 스스로 수행",
                "각 활동에서 신체적인 도움이 필요함 (예: 머리를 감겨주거나 손이 닿지 않는 등 부위를 도와줌)",
                "가슴이나 팔처럼 손이 닿는 부위만 혼자 가능",
                "모든 과정에 참여하지 않음"))));
        out.put("feeding", new Questionnaire(VERSION, List.of(
            new Question("route", "이번 주 음식이나 영양을 어떤 방법으로 섭취하셨나요?", null, "single", true,
                options("oral", "입으로 먹음", "tube", "영양관으로 공급받음", "both", "입으로 먹기와 영양관을 함께 사용함", "unknown", "직접 보지 못함", "not_performed", "이번 주 하지 않음"), null, List.of()),
            assistance("assistance", "입으로 드실 때 어느 정도 도움이 필요했나요?", "음식이 차려진 뒤의 모습을 골라주세요.", when("route", "oral", "both"),
                "음식과 식기를 혼자 다루며 먹음", "가시 제거·음식 자르기·뚜껑 열기 등을 도와주면 혼자 먹음", "본인이 직접 먹으며 뜨기나 입으로 가져가기 일부에 도움", "대부분 떠먹여 드리고 본인이 일부 먹음", "입으로 가져가는 동작을 전부 도와드림"),
            multiple("parts", "어떤 부분에서 직접 도움이 필요했나요?", options("scooping", "음식 뜨기", "to_mouth", "입으로 가져가기", "cup", "컵 사용하기", "other", "기타", "unknown", "직접 보지 못함"), when("assistance", "0", "1", "2")), variation(when("assistance", "0", "1", "2", "3", "4")))));
        return Collections.unmodifiableMap(out);
    }
    public static Questionnaire forItem(String code) { return CATALOG.get(code); }
    public static boolean isV2(SnapshotBody.ItemValues values) { return values != null && Integer.valueOf(VERSION).equals(values.questionnaireVersion()); }
    public static boolean upgradeRequired(SnapshotBody body) {
        return body == null || CATALOG.keySet().stream().anyMatch(code -> !isV2(body.items().get(code)));
    }
    public static Map<String, List<String>> validate(String code, Map<String, List<String>> answers) {
        Questionnaire schema = forItem(code);
        if (schema == null) throw new ValidationException(code + ": 새 질문이 적용되지 않는 항목입니다");
        Map<String, List<String>> given = answers == null ? Map.of() : answers;
        Set<String> known = new HashSet<>();
        schema.questions().forEach(q -> known.add(q.code()));
        for (String key : given.keySet()) if (!known.contains(key)) throw new ValidationException(code + ": 모르는 질문 " + key);
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (Question q : schema.questions()) {
            boolean active = q.when() == null || result.getOrDefault(q.when().question(), List.of()).stream().anyMatch(q.when().anyOf()::contains);
            List<String> selected = given.get(q.code());
            if (!active) {
                if (selected != null && !selected.isEmpty()) throw new ValidationException(code + "/" + q.code() + ": 표시되지 않는 질문의 답은 저장할 수 없습니다");
                continue;
            }
            if (selected == null || selected.isEmpty()) {
                if (q.required()) throw new ValidationException(code + "/" + q.code() + ": 답이 필요합니다");
                continue;
            }
            Set<String> valid = new HashSet<>();
            q.options().forEach(o -> valid.add(o.code()));
            if (selected.stream().anyMatch(v -> v == null || !valid.contains(v)) || new HashSet<>(selected).size() != selected.size())
                throw new ValidationException(code + "/" + q.code() + ": 알 수 없거나 중복된 답입니다");
            if ((q.kind().equals("single") || selected.stream().anyMatch(q.exclusive()::contains)) && selected.size() != 1)
                throw new ValidationException(code + "/" + q.code() + ": 함께 선택할 수 없는 답입니다");
            // Catalog order makes multiple-choice answers stable regardless of click order.
            result.put(q.code(), q.options().stream().map(CatalogDto.CodeLabel::code).filter(selected::contains).toList());
        }
        return Collections.unmodifiableMap(result);
    }
}
