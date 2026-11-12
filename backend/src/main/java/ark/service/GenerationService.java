package ark.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import ark.config.Config;
import ark.dto.ImageGenerationRequest;
import ark.dto.JobDto;
import ark.dto.VideoGenerationRequest;
import ark.entity.GeneratedFile;
import ark.entity.MediaKind;
import ark.integration.byteplus.ArkClient;
import ark.integration.byteplus.ImageGenerator;
import ark.integration.byteplus.Models;
import ark.integration.byteplus.SavedOutput;
import ark.integration.byteplus.VideoGenerator;
import lombok.RequiredArgsConstructor;

/**
 * Seedream (image) and Seedance (video) generation. Requests are validated synchronously (400 on error),
 * then run as background jobs: call API → download → save in library → auto-push to Virtual Portrait.
 */
@Service
@RequiredArgsConstructor
public class GenerationService {
    private final Config config;
    private final JobService jobs;
    private final FileLibraryService fileLibrary;
    private final AssetLibraryService assetLibrary;

    public JobDto generateImage(ImageGenerationRequest req) throws Exception {
        ImageGenerator.Params p = new ImageGenerator.Params();
        p.model = Models.image(req.getModel());
        p.prompt = req.getPrompt();
        p.refs = fileLibrary.resolveRefs(req.getRefs(), MediaKind.IMAGE);
        p.size = req.getSize();
        p.sequential = req.isSequential();
        p.maxImages = req.getMaxImages() == null ? 4 : req.getMaxImages();
        p.watermark = req.isWatermark();
        p.outputFormat = req.getOutputFormat();
        ImageGenerator.buildBody(p); // model-specific validation → 400 now instead of a failed job
        ArkClient client = ArkClient.from(config);
        Path outDir = config.outputDir();

        return jobs.start("image", "Tạo ảnh – " + p.model.name(), ctx -> {
            List<SavedOutput> saved = ImageGenerator.run(client, p, outDir, ctx::log);
            List<GeneratedFile> files = new ArrayList<>();
            for (SavedOutput o : saved) {
                GeneratedFile f = fileLibrary.record(o, p.model.id(), p.prompt, null, ctx.getId());
                files.add(f);
                ctx.addFile(f);
            }
            assetLibrary.autoPush(files, ctx);
        });
    }

    public JobDto generateVideo(VideoGenerationRequest req) throws Exception {
        VideoGenerator.Params p = new VideoGenerator.Params();
        p.model = Models.video(req.getModel());
        p.mode = req.getMode();
        p.prompt = req.getPrompt();
        p.firstFrame = fileLibrary.resolveRef(req.getFirstFrame(), MediaKind.IMAGE);
        p.lastFrame = fileLibrary.resolveRef(req.getLastFrame(), MediaKind.IMAGE);
        p.refImages = fileLibrary.resolveRefs(req.getRefImages(), MediaKind.IMAGE);
        p.refVideos = fileLibrary.resolveRefs(req.getRefVideos(), MediaKind.VIDEO);
        p.refAudios = fileLibrary.resolveRefs(req.getRefAudios(), MediaKind.AUDIO);
        p.resolution = req.getResolution();
        p.ratio = req.getRatio();
        p.duration = req.getDuration();
        p.generateAudio = req.isGenerateAudio();
        p.watermark = req.isWatermark();
        p.seed = req.getSeed();
        p.cameraFixed = req.isCameraFixed();
        p.returnLastFrame = req.isReturnLastFrame();
        p.outputFormat = req.getOutputFormat();
        List<String> notes = new ArrayList<>();
        VideoGenerator.buildBody(p, notes::add); // validate now
        ArkClient client = ArkClient.from(config);
        Path outDir = config.outputDir();

        return jobs.start("video", "Tạo video – " + p.model.name(), ctx -> {
            notes.forEach(ctx::log);
            String taskId = VideoGenerator.submit(client, p, ctx::log);
            ctx.setTaskId(taskId);
            var task = VideoGenerator.waitFor(client, taskId, ctx::log, ctx::isCancelled);
            if (task == null) return;
            List<GeneratedFile> files = new ArrayList<>();
            for (SavedOutput o : VideoGenerator.downloadOutputs(client, task, outDir, ctx::log)) {
                GeneratedFile f = fileLibrary.record(o, p.model.id(), p.prompt, taskId, ctx.getId());
                files.add(f);
                ctx.addFile(f);
            }
            assetLibrary.autoPush(files, ctx);
        });
    }
}
