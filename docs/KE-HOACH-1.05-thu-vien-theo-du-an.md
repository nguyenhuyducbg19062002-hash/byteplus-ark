# Kế hoạch nâng cấp 1.05 – Thư viện ảnh tham chiếu riêng cho từng dự án

Ngày lập: 26/11/2026 · Áp dụng cho: Ark Studio (D:\Dev\byteplus-ark), bản hiện tại 1.04

## 1. Mục tiêu

Nhiều dự án dùng **trùng mã ảnh tham chiếu** (vd dự án A và B đều có `CHAR_BRA_01`) nhưng là **hai nhân vật / hai ảnh khác nhau**.
Yêu cầu: **dự án nào dùng thư viện ảnh của dự án đó**, với cả 3 nguồn tạo video:

| Nguồn | Ảnh gửi đi dưới dạng | Chỗ dễ lẫn dự án |
|---|---|---|
| **BytePlus** (ModelArk / Seedance) | `asset://` (Virtual Portrait) cho ảnh người thật; URL gốc còn hạn; hoặc base64 từ file trên máy | Ghép asset Virtual Portrait **theo tên mã trên cả tài khoản**; tất cả dự án đẩy chung **một asset group** |
| **BOTSD** (botsd.pro) | base64 file trên máy, theo thứ tự clip | Chỉ lẫn nếu mã đã bị gán nhầm ảnh dự án khác (do "Gán tự động" / chọn từ thư viện) |
| **888API** (888api.xin) | link HTTPS (TOS riêng tư 48h / Litterbox 72h), theo thứ tự clip | Như BOTSD; link tạm đã để theo thư mục dự án |

Nguyên tắc chung: **mọi ảnh trong thư viện thuộc về đúng một dự án** (hoặc "chưa thuộc dự án nào"), và mọi chỗ tự động tìm ảnh
theo tên mã **chỉ tìm trong dự án đang chạy**. Ảnh của dự án khác không bao giờ được tự động dùng.

## 2. Hiện trạng (đã kiểm tra trong code 1.04)

Đã riêng theo dự án:
- Mã tham chiếu (`script_reference`) gắn `projectId`; mỗi mã trỏ tới một file thư viện (`fileId`).
- Ảnh tải lên / nhập thư mục / ảnh AI duyệt → `output/assets/<thư mục dự án>/<mã>.<đuôi>`.
- Video ra → `output/videos/<thư mục dự án>/`.
- 888API: ảnh tải lên TOS dưới `ark-studio/888/<thư mục dự án>/`.

Đang lẫn giữa các dự án (cần sửa):
1. **Gán tự động** (`ScriptService.autoMatchReferences`): tìm **toàn bộ thư viện** file có tên trùng mã, lấy ảnh mới nhất → dự án B có thể nhận ảnh của A.
2. **Virtual Portrait (chỉ BytePlus)**:
   - `matchVpAssets` (nút "Ghép asset VP") và `ScriptRunner.linkVpAsset` (tự ghép khi gửi clip) dùng `findAssetFor` = tìm asset Active **theo tên** trên cả tài khoản.
   - `isBlocked` dùng `cachedAssetFor(code)` cũng theo tên → coi mã là "không bị chặn" nhờ asset của dự án khác.
   - Mọi dự án đẩy ảnh vào **chung một asset group** (`config.assetGroup`), tên asset = tên file (`CHAR_BRA_01.png`) → 2 dự án sinh 2 asset trùng tên.
3. **Ô chọn ảnh từ thư viện** (`LibraryPicker`) hiện ảnh của mọi dự án.
4. **Chế độ lưu ảnh "FOLDER"** (`refSaveDir`): nhiều dự án trỏ chung một thư mục thì ảnh trùng mã nằm chung chỗ (đánh số `_v2`…).
5. Bảng `generated_file` không biết file thuộc dự án nào → không lọc được, không kiểm tra được khi chạy.

## 3. Thiết kế

### 3.1 Dữ liệu
- `GeneratedFile.projectId` (Long, null = chưa thuộc dự án nào: ảnh tạo ở trang Tạo ảnh, video lẻ…). Thêm index `ix_generated_file_project`.
- `ScriptProject.vpGroupId` (String, null = chưa tạo): asset group Virtual Portrait riêng của dự án.
- Gán `projectId` ở mọi chỗ tạo file cho dự án: `uploadReference`, nhập thư mục ảnh, `ScriptImageRunner` (ảnh AI + ảnh chờ duyệt),
  `fileForAsset` khi tải asset VP về, video kết quả của clip (`ScriptRunner` cả 3 nguồn).

### 3.2 Chuyển dữ liệu cũ (tự chạy 1 lần khi khởi động – `SchemaFixes`)
1. File có đường dẫn `assets/<folder>/…` hoặc `videos/<folder>/…` trùng thư mục một dự án → `projectId` = dự án đó.
2. File còn lại đang được mã của **đúng một** dự án dùng → thuộc dự án đó.
3. File đang được **nhiều dự án** dùng (trước đây chọn chung) → giữ cho dự án đầu tiên; với mỗi dự án còn lại **sao chép file** vào
   `assets/<folder dự án>/` thành bản riêng và trỏ mã sang bản sao (bản sao chưa có asset VP – sẽ được đẩy lại vào group của dự án khi cần).
4. Ghi log tóm tắt: bao nhiêu file đã gán, bao nhiêu bản sao.

### 3.3 Quy tắc chọn ảnh (chung cho cả 3 nguồn)
- **Gán tự động**: chỉ xét file `projectId = dự án` (còn trên máy), tên trùng mã, mới nhất trước.
- **Chọn từ thư viện**: mặc định chỉ hiện ảnh của dự án đang mở; có công tắc "Xem tất cả dự án". Nếu chọn ảnh của dự án khác /
  chưa thuộc dự án → **sao chép vào thư mục dự án** thành file riêng rồi mới gán (dự án khác sửa / xoá / đẩy VP không ảnh hưởng).
- **Kiểm tra trước khi gửi clip** (bộ điều phối): mã trỏ tới file thuộc dự án khác → không gửi, báo "Ảnh của mã X thuộc dự án Y –
  chọn lại ảnh" (chỉ xảy ra với dữ liệu lạ; luồng bình thường đã sao chép).
- **Chế độ FOLDER**: lưu vào `<refSaveDir>/<tên dự án>/` thay vì thẳng `<refSaveDir>/` (dự án mới; dự án cũ giữ ảnh đã lưu, ảnh mới vào thư mục con).

### 3.4 Theo từng nguồn video

**BytePlus**
- Mỗi dự án một **asset group riêng** tên `<tên dự án> (<folder>)`, app tự tạo lần đầu cần đẩy ảnh (`CreateAssetGroup`), lưu `vpGroupId`.
  Không có quyền tạo group → dùng group chung trong Cài đặt như cũ nhưng đặt tên asset `<folder>__<mã>` để không trùng.
- Đẩy ảnh (`ensureActiveAsset`) vào group của dự án; tên asset = mã (hoặc `<folder>__<mã>`).
- Ghép asset theo tên (`matchVpAssets`, `linkVpAsset`, `isBlocked`): chỉ nhận asset thuộc **group của dự án**, hoặc asset có tên dạng
  `<folder>__<mã>`, hoặc asset **không gắn với file nào của dự án khác** (vd bạn tự tải lên console, group tên = mã) – xét theo thứ tự đó.
  **Không bao giờ** nhận asset mà thư viện ghi là của dự án khác.
- `asset://` đã gắn với file của dự án → dùng như cũ. Asset bị xoá trên BytePlus → app đẩy lại vào group của dự án (giữ cơ chế tự sửa hiện có).
- TOS (để đẩy VP): giữ khoá theo `fileId` (vốn đã riêng); thêm tiền tố thư mục dự án cho dễ dọn.

**BOTSD**
- Không có thư viện phía BOTSD; ảnh gửi base64 từ đúng file của mã → chỉ cần quy tắc 3.3 là đủ.
- Bỏ qua chặn Virtual Portrait như hiện tại; kiểm tra `projectId` trước khi gửi.

**888API**
- Link ảnh tạm: giữ `ark-studio/888/<folder>/<fileId>_<tên>` (đã riêng); bộ nhớ đệm link theo `fileId + chỗ để + folder` (đã có).
- Với Litterbox: tên file gửi lên kèm folder để dễ nhận biết; link vẫn tự xoá sau 72h.
- Kiểm tra `projectId` trước khi gửi như BOTSD.

**Đổi nguồn giữa chừng** (vd BytePlus → 888API): không ảnh hưởng thư viện; mã giữ nguyên ảnh của dự án. Clip đã gửi theo dõi tiếp
ở đúng nguồn cũ (theo tiền tố task `botsd:` / `888:` / không tiền tố).

### 3.5 Giao diện
- Kịch bản › tab Ảnh tham chiếu: mỗi ảnh hiện nhãn dự án; cảnh báo đỏ nếu ảnh không thuộc dự án.
- Ô chọn ảnh (LibraryPicker): lọc theo dự án đang mở, công tắc "Xem tất cả dự án", khi chọn ảnh dự án khác báo "sẽ sao chép vào dự án này".
- Trang Thư viện: bộ lọc "Dự án" (tất cả / chưa thuộc dự án / từng dự án), cột dự án.
- Trang Virtual Portrait: hiện group của từng dự án.
- Cấu hình chạy: dòng giải thích ngắn theo nguồn đang chọn (BytePlus: ảnh người thật đẩy vào group VP riêng của dự án; BOTSD: gửi thẳng file;
  888API: link tạm theo thư mục dự án).

### 3.6 Xoá dự án
- Hỏi thêm: "Xoá cả ảnh tham chiếu của dự án?" (mặc định **không**). Chọn có → xoá file `projectId = dự án` không còn dự án nào dùng,
  và (BytePlus) hỏi riêng có xoá asset group VP của dự án không.

## 4. Các bước làm

| # | Việc | File chính |
|---|---|---|
| 1 | Thêm `projectId` cho `GeneratedFile`, `vpGroupId` cho `ScriptProject`; repository lọc theo dự án | `entity/GeneratedFile.java`, `entity/ScriptProject.java`, `repository/GeneratedFileRepository.java` |
| 2 | Chuyển dữ liệu cũ (3.2) | `config/SchemaFixes.java` |
| 3 | Gán `projectId` khi tạo file cho dự án | `ScriptService`, `ScriptImageRunner`, `AssetLibraryService.fileForAsset`, `ScriptRunner` |
| 4 | Gán tự động chỉ trong dự án; chọn ảnh ngoài dự án → sao chép | `ScriptService.autoMatchReferences`, `setReference` |
| 5 | Kiểm tra ảnh thuộc dự án trước khi gửi (cả 3 nguồn) | `ScriptRunner.RefCheck` |
| 6 | Group VP riêng mỗi dự án + ghép asset có phạm vi dự án | `AssetLibraryService`, `ScriptRunner.linkVpAsset/isBlocked`, `ScriptService.matchVpAssets` |
| 7 | Chế độ FOLDER thêm thư mục con theo dự án | `ScriptImageRunner` |
| 8 | API: `GET /files?projectId=`, `GET /script-projects/{id}/library` | `FileController`, `FileLibraryService` |
| 9 | Giao diện (3.5) | `LibraryPicker.vue`, `ScriptView.vue`, `LibraryView`, `AssetsView.vue` |
| 10 | Xoá dự án kèm ảnh (3.6) | `ScriptService.delete`, `ScriptView.vue` |
| 11 | Test + đóng gói 1.05, commit, tag `v1.05` | |

## 5. Kiểm thử

Tạo 2 dự án A, B cùng có mã `CHAR_BRA_01`, ảnh khác nhau (màu khác để nhận ra):
1. Gán tự động ở B → nhận đúng ảnh của B; B chưa có ảnh → không nhận ảnh của A (báo thiếu).
2. Chọn ảnh của A trong ô chọn ảnh của B → B có bản sao riêng; xoá ảnh ở A không ảnh hưởng B.
3. **BytePlus**: chạy clip ở A và B → mỗi dự án đẩy vào group VP riêng, gửi đúng `asset://` của mình; asset của A không được dùng cho B.
4. **BOTSD** (server giả): base64 gửi đi khớp đúng file của từng dự án.
5. **888API** (server giả): link ảnh nằm đúng thư mục của từng dự án.
6. Dữ liệu cũ: một file đang dùng chung 2 dự án → sau khi chuyển, mỗi dự án một bản riêng, clip vẫn chạy.
7. Đổi nguồn giữa chừng, khởi động lại backend khi clip đang chạy → theo dõi tiếp đúng nguồn, không gửi lại.
8. Chế độ FOLDER: 2 dự án cùng thư mục → ảnh vào 2 thư mục con khác nhau.

## 6. Rủi ro / lưu ý

- Bản sao ảnh tốn thêm dung lượng (ảnh tham chiếu nhỏ, chấp nhận được).
- Bản sao chưa có asset VP → lần đầu chạy BytePlus sẽ đẩy lại (mất thêm vài phút, cần TOS dùng được).
- Tài khoản BytePlus giới hạn số asset group: nếu tạo group bị từ chối → dùng group chung + tên `<folder>__<mã>` (3.4).
- Asset tự tải lên console không gắn dự án: vẫn được dùng nếu không thuộc dự án nào khác – nên đặt group/tên theo dự án để chắc chắn.
