package ark.dto;

import ark.entity.ScriptReference;

/** Mã ảnh tham chiếu + ảnh đã gán (null = còn thiếu) + số clip dùng mã này. */
public record ScriptReferenceDto(Long id, String code, ScriptReference.Kind kind, String name, String description,
                                 GeneratedFileDto file, int usedBy, String imagePrompt, boolean promptEdited,
                                 String identityCode, ScriptReference.ImageStatus imageStatus, String imageError,
                                 String blockedReason) {}
