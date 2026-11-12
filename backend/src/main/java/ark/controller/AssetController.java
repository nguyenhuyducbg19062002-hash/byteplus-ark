package ark.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ark.dto.CreateAssetGroupRequest;
import ark.dto.JobDto;
import ark.dto.PageResponse;
import ark.dto.PushFilesRequest;
import ark.dto.PushUrlRequest;
import ark.service.AssetLibraryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** Virtual Portrait asset library (AK/SK). */
@Validated
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AssetController {
    private final AssetLibraryService assetLibraryService;

    @GetMapping("/asset-groups")
    public List<Map<String, Object>> groups() throws Exception {
        return assetLibraryService.listGroups();
    }

    @PostMapping("/asset-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createGroup(@Valid @RequestBody CreateAssetGroupRequest request) throws Exception {
        return assetLibraryService.createGroup(request);
    }

    @GetMapping("/assets")
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String group,
                                                  @RequestParam(required = false) String name,
                                                  @RequestParam(required = false) String status,
                                                  @RequestParam(defaultValue = "1") @Min(1) int page,
                                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) throws Exception {
        return assetLibraryService.listAssets(group, name, status, page, size);
    }

    @GetMapping("/assets/{id}")
    public Map<String, Object> get(@PathVariable String id) throws Exception {
        return assetLibraryService.getAsset(id);
    }

    /** Push library files (original URL, 24h) – returns a job. */
    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto push(@Valid @RequestBody PushFilesRequest request) {
        return assetLibraryService.pushFiles(request);
    }

    @PostMapping("/assets/from-url")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto pushUrl(@Valid @RequestBody PushUrlRequest request) {
        return assetLibraryService.pushUrl(request);
    }

    @PostMapping("/assets/{id}/downloads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto download(@PathVariable String id) {
        return assetLibraryService.download(id);
    }

    @DeleteMapping("/assets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) throws Exception {
        assetLibraryService.deleteAsset(id);
    }
}
