package nextvisit.engine;

/** 한국어 조사. 마지막 글자의 받침 유무로 고른다. 한글이 아니면 받침 없음으로 본다. */
public final class Josa {

    private Josa() {}

    public static String eunNeun(String word) {
        return word + (hasFinal(word) ? "은" : "는");
    }

    /** "으로/로". 받침이 ㄹ이면 "로". */
    public static String euroRo(String word) {
        if (!hasFinal(word) || finalIsRieul(word)) {
            return word + "로";
        }
        return word + "으로";
    }

    static boolean hasFinal(String word) {
        char c = word.charAt(word.length() - 1);
        if (c < 0xAC00 || c > 0xD7A3) {
            return false;
        }
        return (c - 0xAC00) % 28 != 0;
    }

    private static boolean finalIsRieul(String word) {
        char c = word.charAt(word.length() - 1);
        return c >= 0xAC00 && c <= 0xD7A3 && (c - 0xAC00) % 28 == 8;
    }
}
