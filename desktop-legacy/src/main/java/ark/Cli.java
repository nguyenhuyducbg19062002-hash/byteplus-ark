package ark;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ark.Models.VideoMode;

/** Command-line mode. Messages are ASCII so they display correctly in any Windows console code page. */
final class Cli {
    private Cli() {}

    private static final Set<String> FLAGS = Set.of("no-audio", "wm", "last-frame", "seq", "camera-fixed", "png", "push");

    static int run(String[] args) {
        Config cfg = new Config();
        try {
            Opts o = Opts.parse(args);
            switch (args[0]) {
                case "models": return models();
                case "image": return image(cfg, o);
                case "video": return video(cfg, o);
                case "status": return status(cfg, o);
                case "download": return download(cfg, o);
                case "list": return list(cfg, o);
                case "delete": return delete(cfg, o);
                case "asset-groups": return assetGroups(cfg);
                case "asset-group-create": return assetGroupCreate(cfg, o);
                case "asset-push": return assetPush(cfg, o);
                case "assets": return assets(cfg, o);
                case "asset-get": return assetGet(cfg, o);
                case "asset-delete": return assetDelete(cfg, o);
                default: return usage();
            }
        } catch (ArkException e) {
            System.err.println("Loi API: " + ascii(e.toString()));
            return 2;
        } catch (Exception e) {
            System.err.println("Loi: " + ascii(String.valueOf(e.getMessage())));
            return 1;
        }
    }

    private static int usage() {
        System.out.println(String.join("\n",
                "Cach dung (khong co tham so = mo giao dien):",
                "  run.bat models",
                "  run.bat image \"prompt\" [--model ID] [--size 2K] [--ref file_or_url]... [--seq] [--max 4] [--png] [--wm]",
                "  run.bat video \"prompt\" [--model ID] [--first anh] [--last anh]",
                "                [--ref-image x]... [--ref-video url]... [--ref-audio x]...",
                "                [--res 720p] [--ratio 16:9] [--dur 5] [--no-audio] [--seed N] [--camera-fixed]",
                "                [--last-frame] [--format mp4|mov] [--wm] [--no-wait]",
                "  run.bat status TASK_ID",
                "  run.bat download TASK_ID",
                "  run.bat list [--status succeeded] [--size 20]",
                "  run.bat delete TASK_ID",
                "Virtual Portrait (can Access Key / Secret Key trong Cai dat):",
                "  run.bat asset-groups",
                "  run.bat asset-group-create \"ten group\"",
                "  run.bat asset-push FILE_DA_TAO_HOAC_URL... [--group GROUP_ID]",
                "  run.bat assets [--group GROUP_ID] [--name loc_ten]",
                "  run.bat asset-get ASSET_ID",
                "  run.bat asset-delete ASSET_ID",
                "  (them --push vao lenh image/video de tu day ket qua vao group mac dinh)"));
        return 1;
    }

    private static int assetGroups(Config cfg) throws Exception {
        for (Map<String, Object> g : AssetService.from(cfg).listGroups()) {
            String id = Json.str(g, "Id");
            System.out.printf("%-34s %s%s%n", id, ascii(String.valueOf(Json.str(g, "Name"))),
                    id.equals(cfg.assetGroup()) ? "   <- mac dinh" : "");
        }
        return 0;
    }

    private static int assetGroupCreate(Config cfg, Opts o) throws Exception {
        String id = AssetService.from(cfg).createGroup(o.prompt(), "Created by BytePlus Ark Studio");
        cfg.setAssetGroup(id);
        cfg.save();
        System.out.println(id + "  (da dat lam group mac dinh)");
        return 0;
    }

    private static int assetPush(Config cfg, Opts o) throws Exception {
        String group = o.get("group", cfg.assetGroup());
        AssetService s = AssetService.from(cfg);
        int failed = 0;
        for (String item : o.positional) {
            if (item.startsWith("http://") || item.startsWith("https://")) {
                String path = java.net.URI.create(item).getPath();
                String name = path == null ? "url-asset" : path.substring(path.lastIndexOf('/') + 1);
                String id = s.createAsset(group, item, o.get("type", AssetService.assetType(Path.of(name.isEmpty() ? "x.jpg" : name))), name);
                Map<String, Object> a = s.waitActive(id, Cli::log, () -> false);
                System.out.println("asset://" + id + "  " + Json.str(a, "Status"));
                if (!"Active".equals(Json.str(a, "Status"))) failed++;
            } else {
                Map<String, Object> a = s.pushOutput(Path.of(item), cfg.outputDir(), group, Cli::log, () -> false);
                System.out.println("asset://" + Json.str(a, "Id") + "  " + Json.str(a, "Status"));
                if (!"Active".equals(Json.str(a, "Status"))) failed++;
            }
        }
        return failed == 0 ? 0 : 3;
    }

    private static void pushAll(Config cfg, List<Path> files) {
        AssetService s = AssetService.from(cfg);
        for (Path f : files) {
            try {
                s.pushOutput(f, cfg.outputDir(), cfg.assetGroup(), Cli::log, () -> false);
            } catch (Exception e) {
                System.err.println("Loi day " + f.getFileName() + ": " + ascii(String.valueOf(e.getMessage())));
            }
        }
    }

    private static int assets(Config cfg, Opts o) throws Exception {
        AssetService s = AssetService.from(cfg);
        String token = null;
        do {
            Map<String, Object> r = s.listAssets(o.get("group", cfg.assetGroup()), o.get("name", null), token, 100);
            for (Object a : Json.list(r, "Items")) {
                System.out.printf("asset://%-30s %-6s %-10s %s%n", Json.str(a, "Id"), Json.str(a, "AssetType"),
                        Json.str(a, "Status"), ascii(String.valueOf(Json.str(a, "Name"))));
            }
            token = Json.str(r, "NextToken");
        } while (token != null && !token.isBlank());
        return 0;
    }

    private static int assetGet(Config cfg, Opts o) throws Exception {
        System.out.println(Json.write(AssetService.from(cfg).getAsset(o.prompt())));
        return 0;
    }

    private static int assetDelete(Config cfg, Opts o) throws Exception {
        AssetService.from(cfg).deleteAsset(o.prompt());
        System.out.println("Da xoa asset " + o.prompt());
        return 0;
    }

    private static int models() {
        System.out.println("VIDEO (Seedance):");
        for (Models.VideoModel m : Models.VIDEO) {
            System.out.printf("  %-36s %-22s res=%s dur=%d-%ds%s%s%n", m.id(), m.name(), m.resolutions(),
                    m.minDuration(), m.maxDuration(), m.audio() ? " audio" : "", m.omni() ? " omni-ref" : "");
        }
        System.out.println("IMAGE (Seedream):");
        for (Models.ImageModel m : Models.IMAGE) {
            System.out.printf("  %-36s %-22s size=%s refs<=%d%s%n", m.id(), m.name(), m.sizes(), m.maxRefs(),
                    m.sequential() ? " sequential" : "");
        }
        return 0;
    }

    private static int image(Config cfg, Opts o) throws Exception {
        ImageJob.Params p = new ImageJob.Params();
        p.model = Models.image(o.get("model", Models.IMAGE.get(0).id()));
        p.prompt = o.prompt();
        p.refs = o.all("ref");
        p.size = o.get("size", null);
        p.sequential = o.has("seq");
        p.maxImages = Integer.parseInt(o.get("max", "4"));
        p.watermark = o.has("wm");
        p.outputFormat = o.has("png") ? "png" : null;
        List<Path> files = ImageJob.run(ArkClient.from(cfg), p, cfg.outputDir(), Cli::log);
        files.forEach(f -> System.out.println(f.toAbsolutePath()));
        if (o.has("push") || cfg.autoPush()) pushAll(cfg, files);
        return 0;
    }

    private static int video(Config cfg, Opts o) throws Exception {
        VideoJob.Params p = new VideoJob.Params();
        p.model = Models.video(o.get("model", Models.VIDEO.get(0).id()));
        p.prompt = o.prompt();
        p.firstFrame = o.get("first", null);
        p.lastFrame = o.get("last", null);
        p.refImages = o.all("ref-image");
        p.refVideos = o.all("ref-video");
        p.refAudios = o.all("ref-audio");
        if (p.firstFrame != null && p.lastFrame != null) p.mode = VideoMode.FIRST_LAST;
        else if (p.firstFrame != null) p.mode = VideoMode.FIRST_FRAME;
        else if (!p.refImages.isEmpty() || !p.refVideos.isEmpty() || !p.refAudios.isEmpty()) p.mode = VideoMode.OMNI;
        else p.mode = VideoMode.TEXT;
        p.resolution = o.get("res", null);
        p.ratio = o.get("ratio", null);
        String dur = o.get("dur", null);
        p.duration = dur == null ? null : Integer.parseInt(dur);
        p.generateAudio = !o.has("no-audio");
        p.watermark = o.has("wm");
        String seed = o.get("seed", null);
        p.seed = seed == null ? null : Long.parseLong(seed);
        p.cameraFixed = o.has("camera-fixed");
        p.returnLastFrame = o.has("last-frame");
        p.outputFormat = o.get("format", null);

        ArkClient c = ArkClient.from(cfg);
        String id = VideoJob.submit(c, p, Cli::log);
        if (o.has("no-wait")) {
            System.out.println(id);
            return 0;
        }
        Map<String, Object> task = VideoJob.waitFor(c, id, Cli::log, () -> false);
        List<Path> files = VideoJob.downloadOutputs(c, task, cfg.outputDir(), Cli::log);
        files.forEach(f -> System.out.println(f.toAbsolutePath()));
        if (o.has("push") || cfg.autoPush()) pushAll(cfg, files);
        return 0;
    }

    private static int status(Config cfg, Opts o) throws Exception {
        System.out.println(Json.write(ArkClient.from(cfg).getVideoTask(o.prompt())));
        return 0;
    }

    private static int download(Config cfg, Opts o) throws Exception {
        ArkClient c = ArkClient.from(cfg);
        Map<String, Object> task = c.getVideoTask(o.prompt());
        VideoJob.downloadOutputs(c, task, cfg.outputDir(), Cli::log).forEach(f -> System.out.println(f.toAbsolutePath()));
        return 0;
    }

    private static int delete(Config cfg, Opts o) throws Exception {
        ArkClient.from(cfg).deleteVideoTask(o.prompt());
        System.out.println("Da huy/xoa task " + o.prompt());
        return 0;
    }

    private static int list(Config cfg, Opts o) throws Exception {
        Map<String, Object> r = ArkClient.from(cfg).listVideoTasks(1, Integer.parseInt(o.get("size", "20")),
                o.get("status", null), null);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        System.out.println("Tong: " + Json.str(r, "total"));
        for (Object it : Json.list(r, "items")) {
            Long created = Json.lng(it, "created_at");
            System.out.printf("%-40s %-10s %-34s %s%n", Json.str(it, "id"), Json.str(it, "status"), Json.str(it, "model"),
                    created == null ? "" : f.format(Instant.ofEpochSecond(created)));
        }
        return 0;
    }

    private static void log(String msg) {
        System.out.println("> " + ascii(msg));
    }

    /** Strip Vietnamese diacritics for console output. */
    static String ascii(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd').replace('Đ', 'D').replace('→', '>').replace('–', '-').replace('…', '.');
    }

    private static final class Opts {
        final List<String> positional = new ArrayList<>();
        final Map<String, List<String>> values = new HashMap<>();

        static Opts parse(String[] args) {
            Opts o = new Opts();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    String k = a.substring(2);
                    if (FLAGS.contains(k) || k.equals("no-wait")) {
                        o.values.computeIfAbsent(k, x -> new ArrayList<>()).add("true");
                    } else {
                        if (i + 1 >= args.length) throw new IllegalArgumentException("Thieu gia tri cho --" + k);
                        o.values.computeIfAbsent(k, x -> new ArrayList<>()).add(args[++i]);
                    }
                } else {
                    o.positional.add(a);
                }
            }
            return o;
        }

        String prompt() {
            if (positional.isEmpty()) throw new IllegalArgumentException("Thieu tham so (prompt / task id)");
            return String.join(" ", positional);
        }

        boolean has(String k) { return values.containsKey(k); }

        String get(String k, String def) {
            List<String> v = values.get(k);
            return v == null ? def : v.get(v.size() - 1);
        }

        List<String> all(String k) { return values.getOrDefault(k, List.of()); }
    }
}
