package ark.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ark.dto.ScriptImportResult;
import ark.entity.ScriptClip;
import ark.entity.ScriptProject;
import ark.entity.ScriptReference;
import ark.integration.byteplus.Models;
import ark.repository.ScriptClipRepository;
import ark.repository.ScriptProjectRepository;
import ark.repository.ScriptReferenceRepository;
import lombok.RequiredArgsConstructor;

/**
 * Đọc file Excel kịch bản Seedance (định dạng "KICH BAN SEEDANCE …xlsx"):
 * <ul>
 * <li>sheet "KỊCH BẢN": mỗi dòng có cột MÃ là một clip (prompt tiếng Anh, thứ tự ảnh tham chiếu, thời lượng…);</li>
 * <li>sheet "NHÂN VẬT" / "BỐI CẢNH" / "ĐẠO CỤ": danh sách mã ảnh tham chiếu (CHAR_…, LOC_…, PROP_…).</li>
 * </ul>
 * Cột được nhận theo tên tiêu đề, không theo vị trí. Nhập lại cùng file vào dự án cũ thì cập nhật theo mã clip:
 * clip có prompt / tham chiếu thay đổi được đưa về PENDING, ảnh đã gán cho mã tham chiếu được giữ nguyên.
 */
@Service
@RequiredArgsConstructor
public class ScriptImportService {
    private static final Pattern REF_LINE = Pattern.compile("Ảnh\\s*(\\d+)\\s*=\\s*(?:bối cảnh\\s+|đạo cụ\\s+|nhân vật\\s+)?([A-Za-z0-9_]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern REF_ORDER = Pattern.compile("REFERENCE ORDER:([^\\n]*)");
    private static final Pattern REF_ORDER_ITEM = Pattern.compile("Image\\s*(\\d+)\\s*:\\s*([A-Za-z0-9_]+)");
    private static final Pattern PROMPT_DURATION = Pattern.compile("DURATION:\\s*(\\d+(?:\\.\\d+)?)\\s*SECONDS");
    /** Mã nhân vật trần (không có hậu tố trang phục) xuất hiện trong prompt, vd "CHAR_STR". */
    private static final Pattern BARE_CHAR = Pattern.compile("\\bCHAR_[A-Z]{2,}\\b(?!_)");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$");

    private final ScriptProjectRepository projects;
    private final ScriptClipRepository clips;
    private final ScriptReferenceRepository references;
    private final ScriptService scriptService;

    /** prefix/display/role/look/outfit chỉ có ở nhân vật; sheetPrompt = cột "PROMPT ẢNH" nếu sheet có. */
    private record ParsedRef(String code, String name, String description, String prefix, String display,
                             String role, String look, String outfit, String sheetPrompt) {
        ParsedRef(String code, String name, String description, String sheetPrompt) {
            this(code, name, description, null, null, null, null, null, sheetPrompt);
        }
    }

    private static final Pattern QUOTED = Pattern.compile("[“\"]([^”\"]+)[”\"]");

    private record ParsedClip(String code, String scene, String timecode, int duration, List<String> refs,
                              String prompt, String promptVi, String dialogue, String note, List<String> warnings) {}

    @Transactional
    public ScriptImportResult importExcel(InputStream in, String fileName, Long projectId, String name) throws IOException {
        List<ParsedRef> refs = new ArrayList<>();
        List<ParsedClip> parsed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        try (Workbook wb = WorkbookFactory.create(in)) {
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter fmt = new DataFormatter();
            Sheet script = null;
            for (Sheet s : wb) {
                String n = upper(s.getSheetName());
                if (n.contains("KỊCH BẢN") || n.contains("KICH BAN") || n.contains("SCRIPT")) script = s;
                else if (n.contains("NHÂN VẬT") || n.contains("NHAN VAT") || n.contains("CHARACTER")) readCharacters(s, fmt, eval, refs);
                else if (n.contains("BỐI CẢNH") || n.contains("BOI CANH") || n.contains("ĐẠO CỤ") || n.contains("DAO CU")
                        || n.contains("LOCATION") || n.contains("PROP")) readCodeList(s, fmt, eval, refs);
            }
            if (script == null) throw new IllegalArgumentException("Không thấy sheet KỊCH BẢN trong file " + fileName);
            readClips(script, fmt, eval, parsed, warnings);
        } catch (IllegalArgumentException | IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Không đọc được file Excel: " + e.getMessage(), e);
        }
        if (parsed.isEmpty()) throw new IllegalArgumentException("Sheet KỊCH BẢN không có dòng nào có MÃ clip");

        // Mã tham chiếu: định nghĩa trong sheet nhân vật / bối cảnh / đạo cụ + mọi mã được clip dùng
        Map<String, ParsedRef> refMap = new LinkedHashMap<>();
        for (ParsedRef r : refs) refMap.putIfAbsent(r.code(), r);
        for (ParsedClip c : parsed) {
            for (String code : c.refs()) {
                if (!refMap.containsKey(code)) {
                    refMap.put(code, new ParsedRef(code, null, null, null));
                    if (!refs.isEmpty()) c.warnings().add("Mã " + code + " không có trong sheet nhân vật / bối cảnh / đạo cụ");
                }
            }
        }

        ScriptProject project = projectId != null ? projects.findById(projectId)
                .orElseThrow(() -> new java.util.NoSuchElementException("Không có dự án " + projectId)) : newProject(name, fileName);
        if (project.getStatus() == ScriptProject.Status.RUNNING) {
            throw new IllegalStateException("Dự án đang chạy – bấm Tạm dừng trước khi nhập lại");
        }
        project.setSourceFile(fileName);
        project = projects.save(project);
        Models.VideoModel model = Models.video(project.getModel());
        for (ParsedClip c : parsed) {
            if (c.refs().size() > model.maxRefImages()) {
                c.warnings().add(c.refs().size() + " ảnh tham chiếu > tối đa " + model.maxRefImages() + " của " + model.name());
            }
        }

        // Tên nhân vật để thay mã trong mô tả, và ảnh gốc (bộ đầu) của mỗi nhân vật để giữ khuôn mặt
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> baseOf = new LinkedHashMap<>();
        for (ParsedRef r : refMap.values()) {
            if (r.prefix() == null) continue;
            if (r.display() != null) names.putIfAbsent(r.prefix(), r.display());
            baseOf.putIfAbsent(r.prefix(), r.code());
        }

        // Upsert mã tham chiếu (giữ ảnh đã gán và prompt người dùng đã sửa)
        for (ParsedRef r : refMap.values()) {
            ScriptReference e = references.findByProjectIdAndCode(project.getId(), r.code()).orElseGet(ScriptReference::new);
            e.setProjectId(project.getId());
            e.setCode(r.code());
            e.setKind(ScriptReference.kindOf(r.code()));
            if (r.name() != null || e.getName() == null) e.setName(cut(r.name(), 300));
            if (r.description() != null || e.getDescription() == null) e.setDescription(cut(r.description(), 2000));
            String base = r.prefix() == null ? null : baseOf.get(r.prefix());
            e.setIdentityCode(base != null && !base.equals(r.code()) ? base : null);
            if (!e.isPromptEdited()) e.setImagePrompt(imagePrompt(r, e.getIdentityCode() != null, names));
            references.save(e);
        }

        // Upsert clip theo mã
        int created = 0, updated = 0, unchanged = 0, seq = 0;
        for (ParsedClip c : parsed) {
            seq++;
            ScriptClip e = clips.findByProjectIdAndCode(project.getId(), c.code()).orElse(null);
            String refCodes = String.join(",", c.refs());
            String warn = cut(String.join("\n", c.warnings()), 4000);
            if (e == null) {
                e = new ScriptClip();
                e.setProjectId(project.getId());
                e.setCode(c.code());
                created++;
            } else if (Objects.equals(e.getPrompt(), c.prompt()) && Objects.equals(e.getRefCodes(), refCodes)
                    && e.getDuration() == c.duration()) {
                unchanged++;
            } else {
                updated++;
                if (e.getStatus() != ScriptClip.Status.GENERATING) {
                    e.setStatus(ScriptClip.Status.PENDING);
                    e.setAttempt(0);
                    e.setTaskId(null);
                    e.setError(null);
                }
            }
            e.setSeq(seq);
            e.setSceneTitle(cut(c.scene(), 300));
            e.setTimecode(cut(c.timecode(), 40));
            e.setDuration(c.duration());
            e.setRefCodes(refCodes);
            e.setPrompt(c.prompt());
            e.setPromptVi(c.promptVi());
            e.setDialogue(c.dialogue());
            e.setNote(cut(c.note(), 2000));
            e.setWarnings(warn.isEmpty() ? null : warn);
            clips.save(e);
            for (String w : c.warnings()) warnings.add(c.code() + ": " + w);
        }

        int matched = scriptService.autoMatchReferences(project.getId());
        return new ScriptImportResult(scriptService.toDto(project), created, updated, unchanged, refMap.size(), matched, warnings);
    }

    private static String imagePrompt(ParsedRef r, boolean variant, Map<String, String> names) {
        if (r.sheetPrompt() != null && !r.sheetPrompt().isBlank()) return r.sheetPrompt().trim();
        return switch (ScriptReference.kindOf(r.code())) {
            case CHARACTER -> r.look() == null && r.outfit() == null
                    ? ScriptPromptBuilder.generic(r.code(), r.name(), r.description(), names)
                    : variant ? ScriptPromptBuilder.characterVariant(r.display(), r.look(), r.outfit(), names)
                    : ScriptPromptBuilder.character(r.display(), r.role(), r.look(), r.outfit(), names);
            case LOCATION -> ScriptPromptBuilder.location(r.name() == null ? r.code() : r.name(), r.description(), names);
            case PROP -> ScriptPromptBuilder.prop(r.name() == null ? r.code() : r.name(), r.description(), names);
            default -> ScriptPromptBuilder.generic(r.code(), r.name(), r.description(), names);
        };
    }

    private ScriptProject newProject(String name, String fileName) {
        ScriptProject p = new ScriptProject();
        String base = name != null && !name.isBlank() ? name.trim()
                : fileName.replaceFirst("(?i)\\.xlsx?$", "").trim();
        p.setName(cut(base, 200));
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        p.setFolder((slug.isEmpty() ? "script" : cut(slug, 60)) + "_" + Long.toString(System.currentTimeMillis(), 36));
        p.setModel("dreamina-seedance-2-0-mini-260615"); // model tài khoản đã bật; đổi được trong Cấu hình chạy
        p.setResolution("720p");
        p.setRatio("16:9");
        p.setImageModel(ScriptImageRunner.DEFAULT_IMAGE_MODEL);
        p.setImageSize("2K");
        return p;
    }

    // ---------- sheet KỊCH BẢN ----------

    private void readClips(Sheet s, DataFormatter fmt, FormulaEvaluator eval, List<ParsedClip> out, List<String> warnings) {
        int header = findHeader(s, fmt, eval, "MÃ");
        if (header < 0) throw new IllegalArgumentException("Sheet " + s.getSheetName() + ": không thấy dòng tiêu đề có cột MÃ");
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cCode = find(col, "MÃ"), cPrompt = findContains(col, "SEEDANCE PROMPT", "PROMPT (TIẾNG ANH", "PROMPT");
        if (cCode == null || cPrompt == null) throw new IllegalArgumentException("Sheet " + s.getSheetName() + " cần cột MÃ và SEEDANCE PROMPT");
        Integer cScene = findContains(col, "BỐI CẢNH"), cTime = findContains(col, "TIMECODE"), cDur = findContains(col, "DÀI", "DURATION");
        Integer cRefs = findContains(col, "ẢNH THAM CHIẾU", "REFERENCE"), cVi = findContains(col, "PROMPT TIẾNG VIỆT");
        Integer cDialog = findContains(col, "LỜI THOẠI"), cNote = findContains(col, "LƯU Ý");

        Set<String> seen = new LinkedHashSet<>();
        String scene = null;
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String sceneCell = text(row, cScene, fmt, eval);
            if (!sceneCell.isEmpty()) scene = sceneCell.lines().findFirst().orElse(sceneCell).trim();
            String code = text(row, cCode, fmt, eval).trim();
            if (code.isEmpty()) continue;
            String prompt = text(row, cPrompt, fmt, eval).trim();
            List<String> w = new ArrayList<>();
            if (!CODE.matcher(code).matches()) {
                warnings.add("Dòng " + (r + 1) + ": mã clip không hợp lệ \"" + code + "\" – bỏ qua");
                continue;
            }
            if (!seen.add(code)) {
                warnings.add("Dòng " + (r + 1) + ": trùng mã clip " + code + " – bỏ qua dòng sau");
                continue;
            }
            if (prompt.isEmpty()) {
                warnings.add(code + ": prompt trống – bỏ qua");
                continue;
            }

            // Thứ tự ảnh: cột ẢNH THAM CHIẾU, nếu trống thì lấy dòng REFERENCE ORDER trong prompt
            List<String> refsCol = new ArrayList<>();
            Matcher m = REF_LINE.matcher(text(row, cRefs, fmt, eval));
            while (m.find()) refsCol.add(m.group(2));
            List<String> refsPrompt = new ArrayList<>();
            Matcher om = REF_ORDER.matcher(prompt);
            if (om.find()) {
                Matcher im = REF_ORDER_ITEM.matcher(om.group(1));
                while (im.find()) refsPrompt.add(im.group(2));
            }
            List<String> refs = !refsCol.isEmpty() ? refsCol : refsPrompt;
            if (!refsCol.isEmpty() && !refsPrompt.isEmpty() && !refsCol.equals(refsPrompt)) {
                w.add("Thứ tự ảnh ở cột ẢNH THAM CHIẾU khác dòng REFERENCE ORDER trong prompt – dùng theo cột");
            }
            if (refs.isEmpty()) w.add("Clip không có ảnh tham chiếu");

            // Thời lượng: cột DÀI, nếu trống thì đọc DURATION trong prompt
            int duration = 0;
            String d = text(row, cDur, fmt, eval).replace(',', '.').trim();
            Matcher dm = PROMPT_DURATION.matcher(prompt);
            try {
                if (!d.isEmpty()) duration = (int) Math.round(Double.parseDouble(d));
            } catch (NumberFormatException ignored) {
                w.add("Cột DÀI không phải số: " + d);
            }
            if (dm.find()) {
                int pd = (int) Math.round(Double.parseDouble(dm.group(1)));
                if (duration == 0) duration = pd;
                else if (pd != duration) w.add("Cột DÀI = " + duration + "s nhưng prompt ghi DURATION " + pd + "s");
            }
            if (duration == 0) duration = 15;
            if (duration < 4) {
                w.add("Thời lượng " + duration + "s < 4s tối thiểu của Seedance – tăng lên 4s");
                duration = 4;
            }

            // Nghi lỗi thay chữ: mã nhân vật trần mà clip không có ảnh nhân vật đó (vd "the CHAR_STR" thay cho "street")
            Set<String> bare = new LinkedHashSet<>();
            Matcher bm = BARE_CHAR.matcher(prompt);
            while (bm.find()) {
                String b = bm.group();
                if (refs.stream().noneMatch(x -> x.equals(b) || x.startsWith(b + "_"))) bare.add(b);
            }
            for (String b : bare) {
                w.add("Prompt nhắc " + b + " nhưng clip không có ảnh của nhân vật này – kiểm tra có phải lỗi thay chữ "
                        + "(vd. \"street\" → CHAR_STR, \"police\" → CHAR_POL) không");
            }

            out.add(new ParsedClip(code, scene, text(row, cTime, fmt, eval).trim(), duration, refs, prompt,
                    blankToNull(text(row, cVi, fmt, eval)), blankToNull(text(row, cDialog, fmt, eval)),
                    blankToNull(text(row, cNote, fmt, eval)), w));
        }
    }

    // ---------- sheet NHÂN VẬT: mỗi dòng có MÃ TRANG PHỤC là một ảnh tham chiếu ----------

    private void readCharacters(Sheet s, DataFormatter fmt, FormulaEvaluator eval, List<ParsedRef> out) {
        int header = findHeader(s, fmt, eval, "MÃ TRANG PHỤC");
        if (header < 0) {
            readCodeList(s, fmt, eval, out);
            return;
        }
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cChar = findContains(col, "MÃ NHÂN VẬT"), cOutfit = findContains(col, "MÃ TRANG PHỤC");
        Integer cLook = findContains(col, "NGOẠI HÌNH"), cDress = findContains(col, "BỘ TRANG PHỤC"), cRole = findContains(col, "VAI TRÒ");
        Integer cPrompt = findContains(col, "PROMPT");
        String charName = null, look = null, role = null;
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String c = text(row, cChar, fmt, eval).trim();
            if (!c.isEmpty()) {
                charName = c;
                look = text(row, cLook, fmt, eval).trim();
                role = text(row, cRole, fmt, eval).trim();
            }
            String code = text(row, cOutfit, fmt, eval).trim();
            if (code.isEmpty() || !CODE.matcher(code).matches()) continue;
            String dress = text(row, cDress, fmt, eval).trim();
            String desc = String.join(" · ", List.of(role == null ? "" : role, look == null ? "" : look, dress).stream()
                    .filter(x -> !x.isBlank()).toList());
            String prefix = code.replaceFirst("^(CHAR_[A-Za-z0-9]+)_.*$", "$1");
            String display = prefix;
            if (charName != null) {
                Matcher q = QUOTED.matcher(charName);
                display = q.find() ? q.group(1).trim() : charName;
            }
            out.add(new ParsedRef(code, (charName == null ? "" : charName + " – ") + dress, desc, prefix, display,
                    role, look, dress, blankToNull(text(row, cPrompt, fmt, eval))));
        }
    }

    // ---------- sheet BỐI CẢNH / ĐẠO CỤ: MÃ | TÊN | MÔ TẢ ----------

    private void readCodeList(Sheet s, DataFormatter fmt, FormulaEvaluator eval, List<ParsedRef> out) {
        int header = findHeader(s, fmt, eval, "MÃ");
        if (header < 0) return;
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cCode = find(col, "MÃ"), cName = findContains(col, "TÊN"), cDesc = findContains(col, "MÔ TẢ");
        Integer cPrompt = findContains(col, "PROMPT");
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String code = text(row, cCode, fmt, eval).trim();
            if (code.isEmpty() || !CODE.matcher(code).matches()) continue;
            out.add(new ParsedRef(code, blankToNull(text(row, cName, fmt, eval)), blankToNull(text(row, cDesc, fmt, eval)),
                    blankToNull(text(row, cPrompt, fmt, eval))));
        }
    }

    // ---------- helpers ----------

    private static int findHeader(Sheet s, DataFormatter fmt, FormulaEvaluator eval, String name) {
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), 30); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            for (Cell c : row) {
                String h = upper(fmt.formatCellValue(c, eval)).trim();
                if (h.equals(name) || h.startsWith(name + " ")) return r;
            }
        }
        return -1;
    }

    private static Map<String, Integer> headers(Row row, DataFormatter fmt, FormulaEvaluator eval) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Cell c : row) {
            String h = upper(fmt.formatCellValue(c, eval)).trim();
            if (!h.isEmpty()) m.putIfAbsent(h, c.getColumnIndex());
        }
        return m;
    }

    private static Integer find(Map<String, Integer> col, String exact) {
        return col.get(exact);
    }

    private static Integer findContains(Map<String, Integer> col, String... names) {
        for (String n : names) {
            for (Map.Entry<String, Integer> e : col.entrySet()) if (e.getKey().contains(n)) return e.getValue();
        }
        return null;
    }

    private static String text(Row row, Integer col, DataFormatter fmt, FormulaEvaluator eval) {
        if (col == null) return "";
        Cell c = row.getCell(col);
        return c == null ? "" : fmt.formatCellValue(c, eval).replace("\r\n", "\n");
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.forLanguageTag("vi"));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String cut(String s, int max) {
        return s == null ? null : s.length() > max ? s.substring(0, max) : s;
    }
}
