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
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_@-]{0,99}$");
    /** Mã nhân vật dạng gạch ngang có số bộ trang phục: CHAR-DANIEL-2 → nhân vật CHAR-DANIEL, bộ 2. */
    private static final Pattern HYPHEN_CHAR = Pattern.compile("^(CHAR-[A-Za-z0-9-]+?)-(\\d+)$");
    /** "1. GENERATION GOAL …" → bỏ số thứ tự đầu prompt. */
    private static final Pattern LEADING_NUM = Pattern.compile("^\\s*\\d+\\.\\s+");
    private static final Pattern REQUESTED_DURATION = Pattern.compile("Requested duration:\\s*(\\d+(?:\\.\\d+)?)\\s*seconds",
            Pattern.CASE_INSENSITIVE);
    /** "@LOC-8 = the Crown Room ballroom." trong mục REFERENCE BINDING của prompt video. */
    private static final Pattern BINDING = Pattern.compile("@([A-Za-z]+-[A-Za-z0-9-]+)\\s*=\\s*([^.\\n]{1,200})\\.");
    /** Dòng chú thích "CHAR-BITSY-1 = Bitsy Hollister · trang phục: … · màu: …". */
    private static final Pattern ANNOTATION = Pattern.compile("^\\s*([A-Za-z0-9][A-Za-z0-9_-]*)\\s*=\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern TRAILING_NUM = Pattern.compile("(\\d+)\\s*$");
    private static final String VOICE_TAG = "Thoại tạo ngoài (lồng tiếng sau): ";

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

    /** Một file Excel tải lên. */
    public record Source(InputStream in, String fileName) {}

    /** Loại nội dung trong một file Excel (một file kịch bản kiểu cũ có cả cảnh lẫn mã ảnh). */
    public enum FileKind { SCENES, REFERENCES, VOICE }

    /** Đọc tiêu đề các sheet để biết file chứa gì (không nhập gì). Rỗng = không nhận ra. */
    public static java.util.EnumSet<FileKind> classify(InputStream in) throws IOException {
        java.util.EnumSet<FileKind> kinds = java.util.EnumSet.noneOf(FileKind.class);
        try (Workbook wb = WorkbookFactory.create(in)) {
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            DataFormatter fmt = new DataFormatter();
            for (Sheet s : wb) {
                if (headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE IDS") || h.containsKey("STT SEGMENT")) >= 0) {
                    kinds.add(FileKind.SCENES);
                    continue;
                }
                if (headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE ID")) >= 0) {
                    kinds.add(FileKind.REFERENCES);
                    continue;
                }
                if (headerWith(s, fmt, eval, h -> h.containsKey("ID SEGMENT")
                        && h.keySet().stream().anyMatch(k -> k.contains("THOẠI"))) >= 0) {
                    kinds.add(FileKind.VOICE);
                    continue;
                }
                String n = upper(s.getSheetName());
                if (n.contains("KỊCH BẢN") || n.contains("KICH BAN") || n.contains("SCRIPT")) kinds.add(FileKind.SCENES);
                else if (n.contains("NHÂN VẬT") || n.contains("NHAN VAT") || n.contains("CHARACTER") || n.contains("BỐI CẢNH")
                        || n.contains("BOI CANH") || n.contains("ĐẠO CỤ") || n.contains("DAO CU")) kinds.add(FileKind.REFERENCES);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Không đọc được file Excel: " + e.getMessage(), e);
        }
        return kinds;
    }

    @Transactional
    public ScriptImportResult importExcel(InputStream in, String fileName, Long projectId, String name) throws IOException {
        return importFiles(List.of(new Source(in, fileName)), projectId, name);
    }

    /**
     * Nhập một hoặc nhiều file Excel cùng lúc. Ngoài định dạng kịch bản cũ, nhận bộ file "KET QUA" của tool viết kịch bản:
     * <ul>
     * <li>{@code all-video-prompts.xlsx}: STT segment | Nội dung prompt | Reference IDs | Thời lượng | Tóm tắt (tiếng Việt) | Chú thích ID;</li>
     * <li>{@code reference-prompts.xlsx}: STT | Reference ID | Prompt tạo reference | Chú thích ID – prompt tạo ảnh cho từng mã;</li>
     * <li>{@code voice-overlay.xlsx}: ID segment (E01-29) | Nội dung thoại tạo ngoài – ghi vào ghi chú của clip.</li>
     * </ul>
     * File không nhận ra định dạng được bỏ qua kèm cảnh báo.
     */
    @Transactional
    public ScriptImportResult importFiles(List<Source> sources, Long projectId, String name) throws IOException {
        List<ParsedRef> refs = new ArrayList<>();
        List<ParsedClip> parsed = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, String> voice = new LinkedHashMap<>();
        String fileName = null;
        boolean oldScriptMissing = false;

        for (Source src : sources) {
            boolean used = false;
            int clipsBefore = parsed.size();
            try (Workbook wb = WorkbookFactory.create(src.in())) {
                FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
                DataFormatter fmt = new DataFormatter();
                Sheet script = null;
                boolean oldFormat = false;
                for (Sheet s : wb) {
                    // Bộ file "KET QUA": nhận theo tiêu đề cột
                    if (headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE IDS") || h.containsKey("STT SEGMENT")) >= 0) {
                        readSegments(s, fmt, eval, parsed, refs, warnings);
                        used = true;
                        continue;
                    }
                    if (headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE ID")) >= 0) {
                        readReferencePrompts(s, fmt, eval, refs);
                        used = true;
                        continue;
                    }
                    if (headerWith(s, fmt, eval, h -> h.containsKey("ID SEGMENT")
                            && h.keySet().stream().anyMatch(k -> k.contains("THOẠI"))) >= 0) {
                        readVoice(s, fmt, eval, voice);
                        used = true;
                        continue;
                    }
                    String n = upper(s.getSheetName());
                    if (n.contains("KỊCH BẢN") || n.contains("KICH BAN") || n.contains("SCRIPT")) {
                        script = s;
                        oldFormat = true;
                    } else if (n.contains("NHÂN VẬT") || n.contains("NHAN VAT") || n.contains("CHARACTER")) {
                        readCharacters(s, fmt, eval, refs);
                        oldFormat = true;
                    } else if (n.contains("BỐI CẢNH") || n.contains("BOI CANH") || n.contains("ĐẠO CỤ") || n.contains("DAO CU")
                            || n.contains("LOCATION") || n.contains("PROP")) {
                        readCodeList(s, fmt, eval, refs);
                        oldFormat = true;
                    }
                }
                if (script != null) {
                    readClips(script, fmt, eval, parsed, warnings);
                    used = true;
                } else if (oldFormat) {
                    oldScriptMissing = true;
                    used = true;
                }
            } catch (IllegalArgumentException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Không đọc được file Excel " + src.fileName() + ": " + e.getMessage(), e);
            }
            if (!used) warnings.add(src.fileName() + ": không nhận ra định dạng – bỏ qua");
            if (fileName == null && parsed.size() > clipsBefore) fileName = src.fileName();
        }
        if (parsed.isEmpty()) {
            if (oldScriptMissing) throw new IllegalArgumentException("Không thấy sheet KỊCH BẢN trong file đã chọn");
            if (refs.isEmpty() && voice.isEmpty()) {
                throw new IllegalArgumentException("Không có clip nào: cần sheet KỊCH BẢN (có cột MÃ) hoặc file all-video-prompts.xlsx");
            }
            if (projectId == null) {
                throw new IllegalArgumentException("File chỉ có prompt ảnh tham chiếu / thoại – chọn kèm all-video-prompts.xlsx, "
                        + "hoặc dùng “Nhập lại vào dự án này”");
            }
        }
        if (fileName == null) fileName = sources.isEmpty() ? "kich-ban.xlsx" : sources.get(0).fileName();

        // Mã tham chiếu: định nghĩa trong sheet nhân vật / bối cảnh / đạo cụ + mọi mã được clip dùng
        Map<String, ParsedRef> refMap = new LinkedHashMap<>();
        for (ParsedRef r : refs) {
            ParsedRef old = refMap.get(r.code());
            if (old == null) refMap.put(r.code(), r);
            else if ((old.name() == null && r.name() != null) || (old.description() == null && r.description() != null)) {
                // cùng mã ở nhiều file (vd reference-prompts.xlsx có prompt, all-video-prompts.xlsx có tên): ghép lại
                refMap.put(r.code(), new ParsedRef(old.code(), old.name() != null ? old.name() : r.name(),
                        old.description() != null ? old.description() : r.description(), old.prefix(), old.display(),
                        old.role(), old.look(), old.outfit(), old.sheetPrompt() != null ? old.sheetPrompt() : r.sheetPrompt()));
            }
        }
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
        if (!parsed.isEmpty()) project.setSourceFile(cut(fileName, 300));
        project = projects.save(project);
        // Giới hạn số ảnh / thời lượng của model không lưu thành cảnh báo: màn hình tính theo model đang chọn

        // Tên nhân vật để thay mã trong mô tả, và ảnh gốc (bộ đầu) của mỗi nhân vật để giữ khuôn mặt
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> baseOf = new LinkedHashMap<>();
        for (ParsedRef r : refMap.values()) {
            if (r.prefix() == null) continue;
            if (r.display() != null) names.putIfAbsent(r.prefix(), r.display());
            baseOf.putIfAbsent(r.prefix(), r.code());
        }
        // Mã dạng CHAR-DANIEL-2: bộ gốc là CHAR-DANIEL-1 (số nhỏ nhất của nhân vật), dù thứ tự xuất hiện thế nào
        Map<String, Integer> lowest = new LinkedHashMap<>();
        for (String code : refMap.keySet()) {
            Matcher hm = HYPHEN_CHAR.matcher(code);
            if (!hm.matches()) continue;
            int n = Integer.parseInt(hm.group(2));
            if (n < lowest.getOrDefault(hm.group(1), Integer.MAX_VALUE)) {
                lowest.put(hm.group(1), n);
                baseOf.put(hm.group(1), code);
            }
        }

        // Upsert mã tham chiếu (giữ ảnh đã gán và prompt người dùng đã sửa)
        for (ParsedRef r : refMap.values()) {
            ScriptReference e = references.findByProjectIdAndCode(project.getId(), r.code()).orElseGet(ScriptReference::new);
            e.setProjectId(project.getId());
            e.setCode(r.code());
            e.setKind(ScriptReference.kindOf(r.code()));
            if (r.name() != null || e.getName() == null) e.setName(cut(r.name(), 300));
            if (r.description() != null || e.getDescription() == null) e.setDescription(cut(r.description(), 2000));
            Matcher hm = HYPHEN_CHAR.matcher(r.code());
            String prefix = r.prefix() != null ? r.prefix() : hm.matches() ? hm.group(1) : null;
            String base = prefix == null ? null : baseOf.get(prefix);
            e.setIdentityCode(base != null && !base.equals(r.code()) ? base : null);
            // Mã chỉ được clip nhắc tới (không mô tả / prompt) thì giữ prompt ảnh đã có, vd từ reference-prompts.xlsx nhập trước
            // (mã CHAR-X-1 / LOC-8 của bộ file KET QUA chỉ lấy prompt ảnh từ reference-prompts.xlsx)
            boolean bare = r.sheetPrompt() == null && r.description() == null && r.look() == null && r.outfit() == null;
            boolean ketQua = r.code().indexOf('-') > 0 && r.code().indexOf('_') < 0;
            boolean keep = r.sheetPrompt() == null && e.getImagePrompt() != null && (bare || ketQua);
            if (!e.isPromptEdited() && !keep) {
                e.setImagePrompt(imagePrompt(r, e.getIdentityCode() != null, names));
            }
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

        // Thoại tạo ngoài (voice-overlay.xlsx): ghi vào ghi chú clip theo số segment
        for (Map.Entry<String, String> v : voice.entrySet()) {
            ScriptClip e = clips.findByProjectIdAndCode(project.getId(), v.getKey()).orElse(null);
            if (e == null) {
                warnings.add("Thoại tạo ngoài cho segment " + v.getKey() + ": không có clip này trong dự án");
                continue;
            }
            String old = e.getNote() == null ? "" : e.getNote();
            int at = old.indexOf(VOICE_TAG);
            if (at >= 0) old = old.substring(0, at);
            e.setNote(cut((old.isBlank() ? "" : old.trim() + "\n") + VOICE_TAG + v.getValue(), 2000));
            clips.save(e);
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
            // Nhân vật chỉ có giọng (qua điện thoại / bộ đàm, ngoài hình): "CHAR_ELI's voice …", "the voice of CHAR_ELI" → không cần ảnh
            bare.removeIf(b -> onlyVoice(prompt, b));
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

    // ---------- bộ file "KET QUA": all-video-prompts.xlsx ----------

    private void readSegments(Sheet s, DataFormatter fmt, FormulaEvaluator eval, List<ParsedClip> out, List<ParsedRef> refsOut,
                              List<String> warnings) {
        int header = headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE IDS") || h.containsKey("STT SEGMENT"));
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cCode = findContains(col, "STT"), cPrompt = findContains(col, "PROMPT"), cRefs = findContains(col, "REFERENCE IDS");
        Integer cDur = findContains(col, "THỜI LƯỢNG", "DURATION"), cVi = findContains(col, "TÓM TẮT"), cNote = findContains(col, "CHÚ THÍCH");
        if (cCode == null || cPrompt == null) {
            throw new IllegalArgumentException("Sheet " + s.getSheetName() + " cần cột STT segment và Nội dung prompt");
        }
        Set<String> seen = new LinkedHashSet<>();
        Map<String, String> bindings = new LinkedHashMap<>();
        Map<String, String> notes = new LinkedHashMap<>();
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String code = segmentCode(text(row, cCode, fmt, eval));
            if (code.isEmpty()) continue;
            String prompt = LEADING_NUM.matcher(text(row, cPrompt, fmt, eval).trim()).replaceFirst("");
            if (!CODE.matcher(code).matches()) {
                warnings.add("Dòng " + (r + 1) + ": STT segment không hợp lệ \"" + code + "\" – bỏ qua");
                continue;
            }
            if (!seen.add(code)) {
                warnings.add("Dòng " + (r + 1) + ": trùng segment " + code + " – bỏ qua dòng sau");
                continue;
            }
            if (prompt.isEmpty()) {
                warnings.add(code + ": prompt trống – bỏ qua");
                continue;
            }
            List<String> w = new ArrayList<>();
            List<String> refs = new ArrayList<>();
            for (String x : text(row, cRefs, fmt, eval).split("[;,\\n]+")) {
                // "CHAR-X", "@CHAR-X" hoặc "@Image 1 = CHAR-X" (thứ tự upload → @ImageN): lấy mã sau dấu =
                String c = x.trim();
                if (c.contains("=")) c = c.substring(c.lastIndexOf('=') + 1).trim();
                c = c.replaceFirst("^@", "").trim();
                if (c.isEmpty()) continue;
                if (!CODE.matcher(c).matches()) w.add("Mã tham chiếu không hợp lệ: " + c);
                else if (!refs.contains(c)) refs.add(c);
            }
            if (refs.isEmpty()) w.add("Clip không có ảnh tham chiếu");

            int duration = 0;
            String d = text(row, cDur, fmt, eval).replace(',', '.').trim();
            try {
                if (!d.isEmpty()) duration = (int) Math.round(Double.parseDouble(d));
            } catch (NumberFormatException ignored) {
                w.add("Cột Thời lượng không phải số: " + d);
            }
            Matcher dm = REQUESTED_DURATION.matcher(prompt);
            if (dm.find()) {
                int pd = (int) Math.round(Double.parseDouble(dm.group(1)));
                if (duration == 0) duration = pd;
                else if (pd != duration) w.add("Cột Thời lượng = " + duration + "s nhưng prompt ghi " + pd + "s");
            }
            if (duration == 0) duration = 15;
            if (duration < 4) {
                w.add("Thời lượng " + duration + "s < 4s tối thiểu của Seedance – tăng lên 4s");
                duration = 4;
            }

            Matcher bm = BINDING.matcher(prompt);
            while (bm.find()) bindings.putIfAbsent(bm.group(1), bm.group(2).trim());
            Matcher am = ANNOTATION.matcher(text(row, cNote, fmt, eval));
            while (am.find()) notes.putIfAbsent(am.group(1), am.group(2));

            out.add(new ParsedClip(code, null, null, duration, refs, prompt, blankToNull(text(row, cVi, fmt, eval)), null, null, w));
        }
        // Tên mã: lấy từ chú thích ID (nhân vật) hoặc dòng REFERENCE BINDING trong prompt (bối cảnh / đạo cụ)
        Set<String> codes = new LinkedHashSet<>(notes.keySet());
        codes.addAll(bindings.keySet());
        for (String code : codes) {
            if (!CODE.matcher(code).matches()) continue;
            String note = notes.get(code);
            refsOut.add(new ParsedRef(code, note != null ? annotationName(note) : bindings.get(code), note, null));
        }
    }

    // ---------- bộ file "KET QUA": reference-prompts.xlsx ----------

    private void readReferencePrompts(Sheet s, DataFormatter fmt, FormulaEvaluator eval, List<ParsedRef> out) {
        int header = headerWith(s, fmt, eval, h -> h.containsKey("REFERENCE ID"));
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cCode = find(col, "REFERENCE ID"), cPrompt = findContains(col, "PROMPT"), cNote = findContains(col, "CHÚ THÍCH");
        List<ParsedRef> read = new ArrayList<>();
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String code = text(row, cCode, fmt, eval).trim();
            if (code.isEmpty() || !CODE.matcher(code).matches()) continue;
            String prompt = LEADING_NUM.matcher(text(row, cPrompt, fmt, eval).trim()).replaceFirst("");
            String note = null;
            Matcher am = ANNOTATION.matcher(text(row, cNote, fmt, eval));
            while (am.find()) if (am.group(1).equals(code)) note = am.group(2);
            read.add(new ParsedRef(code, note == null ? null : annotationName(note), note, blankToNull(prompt)));
        }
        out.addAll(0, read); // đứng trước mã chỉ có tên (từ all-video-prompts) để giữ prompt và thứ tự này
    }

    // ---------- bộ file "KET QUA": voice-overlay.xlsx ----------

    private void readVoice(Sheet s, DataFormatter fmt, FormulaEvaluator eval, Map<String, String> out) {
        int header = headerWith(s, fmt, eval, h -> h.containsKey("ID SEGMENT"));
        Map<String, Integer> col = headers(s.getRow(header), fmt, eval);
        Integer cId = find(col, "ID SEGMENT"), cText = null;
        for (Map.Entry<String, Integer> e : col.entrySet()) if (cText == null && e.getKey().contains("THOẠI")) cText = e.getValue();
        for (int r = header + 1; r <= s.getLastRowNum(); r++) {
            Row row = s.getRow(r);
            if (row == null) continue;
            String id = text(row, cId, fmt, eval).trim();
            String line = text(row, cText, fmt, eval).trim();
            if (id.isEmpty() || line.isEmpty()) continue;
            Matcher m = TRAILING_NUM.matcher(id);
            String code = m.find() ? segmentCode(m.group(1)) : id;
            out.merge(code, line, (a, b) -> a + " / " + b);
        }
    }

    /** Mọi lần prompt nhắc mã nhân vật này đều là giọng nói (CHAR_X's voice / voice of CHAR_X), không phải người trên hình. */
    static boolean onlyVoice(String prompt, String code) {
        String q = Pattern.quote(code);
        int all = 0, voice = 0;
        Matcher m = Pattern.compile("\\b" + q + "\\b(?!_)").matcher(prompt);
        while (m.find()) all++;
        Matcher v = Pattern.compile("(?i)\\b" + q + "(?:'s|’s)\\s+(?:\\w+\\s+){0,2}voice\\b|\\bvoice\\s+of\\s+" + q + "\\b").matcher(prompt);
        while (v.find()) voice++;
        return all > 0 && voice >= all;
    }

    /** STT segment "1" / "1.0" → mã clip "001" (sắp xếp đúng, làm lại theo "001-005" được). */
    static String segmentCode(String raw) {
        String t = raw == null ? "" : raw.trim().replaceFirst("\\.0+$", "");
        return t.matches("\\d{1,3}") ? String.format("%03d", Integer.parseInt(t)) : t;
    }

    /** "Bitsy Hollister · trang phục: society cocktail dress · màu: …" → "Bitsy Hollister – society cocktail dress". */
    static String annotationName(String note) {
        String[] parts = note.split("\\s*·\\s*");
        String name = parts[0].trim();
        for (String p : parts) {
            if (p.toLowerCase(Locale.ROOT).startsWith("trang phục:")) name += " – " + p.substring(p.indexOf(':') + 1).trim();
        }
        return name;
    }

    // ---------- helpers ----------

    /** Dòng tiêu đề (trong 30 dòng đầu) thoả điều kiện trên tập tên cột viết hoa; -1 nếu không có. */
    private static int headerWith(Sheet s, DataFormatter fmt, FormulaEvaluator eval,
                                  java.util.function.Predicate<Map<String, Integer>> test) {
        for (int r = s.getFirstRowNum(); r <= Math.min(s.getLastRowNum(), 30); r++) {
            Row row = s.getRow(r);
            if (row != null && r >= 0 && test.test(headers(row, fmt, eval))) return r;
        }
        return -1;
    }

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
