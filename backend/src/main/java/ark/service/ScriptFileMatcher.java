package ark.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ark.dto.ScriptFileMatchDto;
import ark.entity.ScriptReference;
import ark.entity.ScriptReference.Kind;

/**
 * Ghép file ảnh trong thư mục (vd "Anh REF/NHAN VAT/…", "BOI CANH/…", "DAO CU/…") với mã tham chiếu của kịch bản.
 * Thứ tự ưu tiên:
 * <ol>
 * <li>tên file chứa đúng mã (CHAR_YVO_NAVYTEE_01) – 100;</li>
 * <li>tên file chứa mã bỏ tiền tố (YVO_NAVYTEE_01) – 95;</li>
 * <li>thư mục cho biết loại + số thứ tự (BOI CANH/02.jpg → LOC_02_…; NHAN VAT/YVO 2.jpg → bộ thứ 2 của CHAR_YVO) – 85;</li>
 * <li>trùng từ với tên / trang phục / mô tả (bỏ dấu tiếng Việt) – tối đa 75.</li>
 * </ol>
 * Mỗi mã và mỗi file chỉ được ghép một lần, điểm cao ghép trước.
 */
public final class ScriptFileMatcher {
    private ScriptFileMatcher() {}

    public record FileIn(String key, String path) {}

    private static final int MIN_SCORE = 30;
    private static final Pattern NUM = Pattern.compile("^\\d{1,3}$");
    private static final Pattern CODE_NUM = Pattern.compile("_(\\d{1,3})(?:_|$)");
    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "VA", "CUA", "NHA", "CO", "CHO", "VOI", "MOT", "CAC", "NHUNG", "TREN", "DUOI", "TRONG", "NGOAI", "KHONG", "LA", "BO",
            "CAI", "CHIEC", "HOAC", "VAI", "TO", "NHO", "LON", "IMG", "IMAGE", "ANH", "HINH", "PHOTO", "REF", "FINAL", "COPY",
            "BAN", "SAO", "CHEP", "NEW", "V", "THE", "OF", "AND", "WITH", "A", "AN", "PNG", "JPG", "JPEG", "WEBP",
            "CHAR", "LOC", "PROP", "THOI", "DIEM", "NGAY", "CLIP", "NHAN", "VAT", "BOI", "DAO", "CU"));

    private static final class Ref {
        String code;
        Kind kind;
        String codeNorm;
        String shortNorm;
        Integer number;
        String charShort;
        Set<String> tokens = new LinkedHashSet<>();
        Set<String> nameTokens = new LinkedHashSet<>();
    }

    public static List<ScriptFileMatchDto> match(List<ScriptReference> refs, List<FileIn> files) {
        List<Ref> rs = new ArrayList<>();
        Map<String, Integer> outfitIndex = new HashMap<>();
        for (ScriptReference r : refs) {
            Ref x = new Ref();
            x.code = r.getCode();
            x.kind = r.getKind();
            x.codeNorm = norm(r.getCode());
            String[] parts = x.codeNorm.split(" ");
            x.shortNorm = parts.length > 2 ? String.join(" ", Arrays.copyOfRange(parts, 1, parts.length)) : null;
            Matcher m = CODE_NUM.matcher(r.getCode());
            Integer last = null;
            while (m.find()) last = Integer.valueOf(m.group(1));
            if (x.kind == Kind.CHARACTER) {
                x.charShort = parts.length > 1 ? parts[1] : null;
                // số thứ tự bộ trang phục trong cùng nhân vật (theo hậu tố _01, _02… hoặc theo thứ tự xuất hiện)
                x.number = last != null ? last : outfitIndex.merge(String.valueOf(x.charShort), 1, Integer::sum);
                x.nameTokens.addAll(tokens(nameOf(r.getName())));
                x.tokens.addAll(tokens(r.getName()));
            } else {
                Matcher n = Pattern.compile("^[A-Z]+_(\\d{1,3})").matcher(r.getCode());
                x.number = n.find() ? Integer.valueOf(n.group(1)) : last;
                x.tokens.addAll(tokens(r.getName()));
                x.tokens.addAll(tokens(r.getDescription()));
            }
            for (String p : parts) if (p.length() > 2 && !NUM.matcher(p).matches() && !STOP.contains(p)) x.tokens.add(p);
            rs.add(x);
        }

        record Cand(int file, Ref ref, int score, String reason) {}
        List<Cand> cands = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            String path = files.get(i).path().replace('\\', '/');
            int slash = path.lastIndexOf('/');
            String folder = slash < 0 ? "" : norm(path.substring(0, slash));
            String stem = norm(path.substring(slash + 1).replaceFirst("\\.[^.]+$", ""));
            String padded = " " + stem + " ";
            Kind folderKind = kindOf(folder);
            Set<String> stemTokens = tokens(stem);
            List<Integer> nums = new ArrayList<>();
            for (String t : stem.split(" ")) if (NUM.matcher(t).matches()) nums.add(Integer.valueOf(t));

            for (Ref r : rs) {
                boolean kindOk = folderKind == null || folderKind == r.kind;
                if (padded.contains(" " + r.codeNorm + " ")) {
                    cands.add(new Cand(i, r, 100, "tên file chứa mã"));
                    continue;
                }
                if (r.shortNorm != null && padded.contains(" " + r.shortNorm + " ") && kindOk) {
                    cands.add(new Cand(i, r, 95, "tên file chứa mã rút gọn"));
                    continue;
                }
                if (!kindOk) continue;
                if (r.kind == Kind.CHARACTER) {
                    boolean who = (r.charShort != null && padded.contains(" " + r.charShort + " "))
                            || r.nameTokens.stream().anyMatch(stemTokens::contains);
                    if (who && r.number != null && nums.contains(r.number)) {
                        cands.add(new Cand(i, r, 85, "đúng nhân vật + số bộ trang phục " + r.number));
                        continue;
                    }
                    int common = (int) stemTokens.stream().filter(r.tokens::contains).count();
                    if (who && common > 0) {
                        int s = Math.min(80, 45 + common * 10);
                        cands.add(new Cand(i, r, s, "đúng nhân vật, trùng " + common + " từ mô tả trang phục"));
                    } else if (who) {
                        cands.add(new Cand(i, r, 40, "đúng tên nhân vật"));
                    } else if (common >= 2) {
                        cands.add(new Cand(i, r, Math.min(60, 20 + common * 10), "trùng " + common + " từ mô tả"));
                    }
                    continue;
                }
                if (folderKind == r.kind && r.number != null && nums.contains(r.number)) {
                    cands.add(new Cand(i, r, 85, "thư mục " + (r.kind == Kind.LOCATION ? "bối cảnh" : "đạo cụ") + " + số " + r.number));
                    continue;
                }
                if (stemTokens.isEmpty()) continue;
                long common = stemTokens.stream().filter(r.tokens::contains).count();
                if (common == 0) continue;
                int s = (int) Math.round(75.0 * common / stemTokens.size());
                if (folderKind == null) s -= 10;
                if (s >= MIN_SCORE) cands.add(new Cand(i, r, s, "trùng " + common + "/" + stemTokens.size() + " từ với tên / mô tả"));
            }
        }
        cands.sort(Comparator.comparingInt(Cand::score).reversed());
        Map<Integer, Cand> byFile = new HashMap<>();
        Set<String> used = new HashSet<>();
        for (Cand c : cands) {
            if (c.score() < MIN_SCORE || byFile.containsKey(c.file()) || used.contains(c.ref().code)) continue;
            byFile.put(c.file(), c);
            used.add(c.ref().code);
        }
        List<ScriptFileMatchDto> out = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            Cand c = byFile.get(i);
            out.add(c == null ? new ScriptFileMatchDto(files.get(i).key(), null, 0, "không đoán được – chọn mã bằng tay")
                    : new ScriptFileMatchDto(files.get(i).key(), c.ref().code, c.score(), c.reason()));
        }
        return out;
    }

    static Kind kindOf(String folderNorm) {
        String f = " " + folderNorm + " ";
        Kind k = null;
        int best = -1;
        // lấy thư mục gần file nhất
        for (Map.Entry<String, Kind> e : Map.of(" NHAN VAT ", Kind.CHARACTER, " CHARACTER ", Kind.CHARACTER, " CHARACTERS ", Kind.CHARACTER,
                " BOI CANH ", Kind.LOCATION, " LOCATION ", Kind.LOCATION, " LOCATIONS ", Kind.LOCATION, " DAO CU ", Kind.PROP,
                " PROP ", Kind.PROP, " PROPS ", Kind.PROP).entrySet()) {
            int at = f.lastIndexOf(e.getKey());
            if (at > best) {
                best = at;
                k = e.getValue();
            }
        }
        return k;
    }

    /** CHAR_YVO “Yvonne Mitchell” – … → "Yvonne Mitchell". */
    private static String nameOf(String name) {
        if (name == null) return "";
        Matcher m = Pattern.compile("[“\"]([^”\"]+)[”\"]").matcher(name);
        return m.find() ? m.group(1) : "";
    }

    static String norm(String s) {
        if (s == null) return "";
        String t = Normalizer.normalize(s.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return t.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new LinkedHashSet<>();
        for (String t : norm(s).split(" ")) {
            if (t.length() < 2 || NUM.matcher(t).matches() || STOP.contains(t)) continue;
            out.add(t);
        }
        return out;
    }
}
