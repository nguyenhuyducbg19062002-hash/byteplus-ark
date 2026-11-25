# BytePlus Ark Studio

Web app tạo **ảnh (Seedream)** và **video (Seedance)** bằng API **BytePlus ModelArk**, có đẩy kết quả lên thư viện **Virtual Portrait** để dùng lại làm tham chiếu.
Tách riêng **frontend Vue 3** và **backend Java (Spring Boot)**.

```
frontend\  Vue 3 + Vite + Pinia + Tailwind CSS + Element Plus   http://localhost:5173 (proxy /api → 8080)
backend\   Spring Boot 3.2 / Java 17 / JPA (H2) / Lombok         http://127.0.0.1:8080/api/v1
data\      database H2 (lịch sử file, asset, job) – tự tạo khi chạy
output\    ảnh / video đã tạo
docs\api\  tài liệu API BytePlus gốc (markdown)
CLAUDE.md  quy tắc kiến trúc của project
desktop-legacy\  bản app desktop Swing cũ (không còn dùng, có thể xoá)
```

### Kiến trúc

Backend (`backend/src/main/java/ark`), theo tầng Controller → Service → Repository:

| Package | Vai trò |
|---|---|
| `controller` | REST `/api/v1/...`, mỏng, nhận DTO đã `@Valid` |
| `service` | nghiệp vụ: `GenerationService`, `VideoTaskService`, `AssetLibraryService`, `FileLibraryService`, `JobService`, `SettingsService` |
| `repository` / `entity` | Spring Data JPA: `GeneratedFile` (file đã tạo + URL gốc + asset ID), `JobRecord` (lịch sử job) |
| `dto` | request (Lombok `@Data` + `@NotBlank`, `@Size`, `@Pattern`…) và response (record) |
| `integration.byteplus` | client BytePlus: `ArkClient` (API key), `OpenApiClient` (ký AK/SK), `ImageGenerator`, `VideoGenerator`, `AssetApi` |
| `exception` | `GlobalExceptionHandler`: mọi lỗi trả về `{error, code, fields}` |
| `config` | `Config` (đọc file cấu hình chứa key), CORS |

Frontend (`frontend/src`): Pinia (`stores/settings`, `models`, `references`, `ui`). `composables/useAsync` kết hợp `<AsyncState>` để mọi lời gọi API đều có trạng thái loading, lỗi (kèm nút Thử lại) và trống. `<StatusBadge>` hiển thị trạng thái dạng viên thuốc (xanh/vàng/đỏ/xám). Có dark mode.

## App desktop (dùng hằng ngày)

```bat
packaging\build-desktop.bat     :: build giao diện + backend, đóng gói kèm Java, cài vào máy
```
Sau đó mở **Ark Studio** trên Desktop / Start Menu – không cần cmd, npm, mvn hay trình duyệt:
- Cài ở `%LOCALAPPDATA%\Programs\Ark Studio` (Java thu gọn bằng jlink + jar có sẵn giao diện). Lối tắt chạy `runtime\bin\javaw.exe`
  (chữ ký Oracle) chứ không phải exe tự build – loại exe chưa ký hay bị diệt virus (Avast) chặn / cách ly.
- Giao diện mở trong cửa sổ riêng (Edge chế độ app, hồ sơ riêng `%USERPROFILE%\.byteplus-ark\webview`), backend nghe `127.0.0.1:18080`.
- Biểu tượng ở khay hệ thống: Mở Ark Studio · Mở thư mục lưu · Thoát. Đóng cửa sổ khi đang chạy clip / ảnh → app chạy nền, xong việc tự thoát;
  không có việc thì thoát luôn. Mở lại khi app đang chạy → chỉ mở thêm cửa sổ.
- Dữ liệu: DB `%USERPROFILE%\.byteplus-ark\data`, log `%USERPROFILE%\.byteplus-ark\logs\ark-studio.log`, key ở `config.properties` cùng chỗ.
  Ảnh / video theo **Thư mục lưu** (Cài đặt); chưa đặt thì mặc định `Documents\BytePlus Ark Studio`. Cài lại / cập nhật không mất dữ liệu.
- Cập nhật: thoát app (khay › Thoát) rồi chạy lại `packaging\build-desktop.bat`.

## Chạy bản dev

Yêu cầu: JDK 17+, Node 18+. Không cần cài Maven vì đã có `mvnw`.

```bat
start-all.bat        :: mở 2 cửa sổ (backend + frontend) rồi mở trình duyệt
:: hoặc chạy riêng:
start-backend.bat    :: cd backend && mvnw spring-boot:run
start-frontend.bat   :: cd frontend && npm install && npm run dev
```

Lần đầu: vào trang **Cài đặt**, nhập API key (để tạo ảnh/video) và AK/SK + Project (để dùng Virtual Portrait), rồi bấm **Kiểm tra kết nối**.
Key chỉ lưu ở backend (`%USERPROFILE%\.byteplus-ark\config.properties`). Frontend chỉ nhận bản đã che. Backend chỉ nghe trên `127.0.0.1`.

Build bản chạy thật:
```bat
cd backend  && mvnw.cmd -DskipTests package   :: → target\byteplus-ark-backend.jar
cd frontend && npm run build                   :: → dist\ (cần web server proxy /api về backend)
```

## Các trang

| Trang | Chức năng |
|---|---|
| Tạo ảnh | Seedream 4.0–5.0: text → ảnh, ảnh tham chiếu (tải lên / thư viện / URL), chuỗi ảnh, kích thước |
| Tạo video | Seedance 1.0–2.5: văn bản, khung đầu, khung đầu + cuối, tham chiếu ảnh/video/audio (`asset://`) |
| Kịch bản hàng loạt | nhập Excel kịch bản Seedance → gán ảnh cho mã CHAR_/LOC_/PROP_ → tự gửi từng clip, song song, tự thử lại, làm lại theo mã |
| Virtual Portrait | chọn/tạo group, bật tự đẩy, danh sách asset, đẩy từ thư viện/URL, dùng làm tham chiếu, tải về, xoá |
| Thư viện | ảnh/video đã lưu trong `output\`: xem, đẩy VP, chuyển sang trang Ảnh/Video làm tham chiếu, xoá |
| Task video | task Seedance trên BytePlus: tải lại, huỷ/xoá |
| Tiến trình | log các job chạy nền (tạo ảnh/video, đẩy asset, tải file) |
| Cài đặt | API key, AK/SK, Project, thư mục lưu |

## Kịch bản hàng loạt (Excel → video tự động)
Luồng giống Flow Batch Studio nhưng chạy ngay trong backend (không cần worker riêng):

1. **Nhập Excel** (`POST /api/v1/script-projects/import`): sheet `KỊCH BẢN` – mỗi dòng có cột `MÃ` là một clip
   (cột `SEEDANCE PROMPT`, `ẢNH THAM CHIẾU` theo thứ tự “Ảnh 1 = …”, `DÀI (s)`, `LỜI THOẠI`…, nhận theo tên tiêu đề);
   sheet `NHÂN VẬT` (cột `MÃ TRANG PHỤC`), `BỐI CẢNH`, `ĐẠO CỤ` – danh sách mã ảnh tham chiếu.
   Nhập lại cùng file thì cập nhật theo mã clip, giữ ảnh đã gán; clip đổi prompt / tham chiếu quay về chờ chạy.
   Khi nhập, app cảnh báo: mã thiếu, quá số ảnh của model, thứ tự ảnh lệch với `REFERENCE ORDER`, và mã nhân vật “trần”
   trong prompt mà clip không có ảnh (thường là lỗi thay chữ, vd `street` → `CHAR_STR`).
   **Bộ file kết quả của tool viết kịch bản** (thư mục kiểu `KET QUA - IDE 21`): bấm **Nhập thư mục kết quả** và chọn cả thư mục,
   hoặc **Nhập Excel mới** rồi chọn nhiều file cùng lúc. App đọc `all-video-prompts.xlsx` (mỗi dòng = 1 clip, mã `001`…, cột Reference IDs
   `CHAR-DANIEL-1; LOC-8; PROP-2` theo đúng thứ tự ảnh, Thời lượng, Tóm tắt tiếng Việt), `reference-prompts.xlsx` (prompt tạo ảnh từng mã;
   `CHAR-X-2…` tự dùng ảnh `CHAR-X-1` làm gốc khuôn mặt) và `voice-overlay.xlsx` (thoại lồng tiếng sau, ghi vào ghi chú clip `E01-29` → `029`).
   Ảnh trong `Ảnh tham chiếu/` (tên file = mã, vd `CHAR-DANIEL-1.jpg`) được mở sẵn trong hộp thoại Nhập thư mục ảnh; bỏ qua `Ảnh chờ duyệt/`.
2. **Gán ảnh** cho từng mã: **tạo bằng AI** (Seedream, prompt tự sinh từ ngoại hình / trang phục / mô tả trong Excel,
   sửa được từng mã; nhân vật nhiều bộ đồ thì bộ đầu tạo trước và được gửi kèm làm tham chiếu khuôn mặt cho các bộ sau;
   nếu sheet có cột chứa chữ `PROMPT` thì dùng prompt đó), tải lên, chọn từ thư viện, đặt tên file đúng mã rồi bấm “Tự ghép theo tên file”, hoặc **Nhập thư mục ảnh**
   (chọn / kéo thả cả thư mục, vd `Anh REF/NHAN VAT|BOI CANH|DAO CU`): app đoán mã cho từng ảnh theo mã trong tên file →
   mã rút gọn → thư mục + số thứ tự → tên nhân vật / mô tả (bỏ dấu), cho xem và sửa trước khi nhập; ảnh &gt; 8 MB / 4096px được thu nhỏ.
   **Duyệt ảnh AI**: ảnh Seedream tạo xong vào mục *chờ duyệt* `output/assets/<dự án>/_cho-duyet/<mã>_<thời gian>.jpeg`, chưa dùng cho clip.
   Bấm **Duyệt** (hoặc “Duyệt tất cả”) → đổi tên thành `output/assets/<dự án>/<mã>.jpeg` (tự tạo thư mục nếu chưa có), ảnh cũ của mã chuyển vào `_cu/`,
   và gán cho mã (không đẩy Virtual Portrait lúc duyệt – bộ chạy video tự đẩy khi gửi clip); **Tạo lại** / **Bỏ ảnh mới** thì xoá ảnh chờ duyệt. Clip dùng mã đang tạo / chờ duyệt ảnh sẽ đứng chờ.
   Cấu hình chạy › **Lưu ảnh đã duyệt**: chỉ thư mục dự án, hoặc chép thêm vào thư mục riêng `<thư mục>/NHAN VAT|BOI CANH|DAO CU|KHAC/<mã>.jpeg`
   (tự tạo thư mục, file trùng tên chuyển vào `_cu/`). Nút **Tải hết ảnh về (.zip)** tải mọi ảnh tham chiếu đã gán.
   Nhân vật nhiều bộ đồ: các bộ sau chỉ tạo khi ảnh bộ đầu đã được duyệt. Bật “Tự duyệt ảnh AI” trong Cấu hình chạy để bỏ qua bước này.
   Nút “Tạo ảnh thiếu + chạy video” làm cả hai: clip nào đủ ảnh thì tự chạy video, dự án không tạm dừng khi ảnh còn đang tạo.
   Ảnh có asset Virtual Portrait Active được gửi dạng `asset://` (tránh Seedance chặn mặt người thật), nếu không thì URL gốc / base64.
3. **Chạy**: cứ 1,5 giây bộ chạy (`ScriptRunner`) lấy clip chờ (ưu tiên clip làm lại, rồi theo thứ tự kịch bản), chỉ lấy clip đủ ảnh,
   tối đa *N* clip song song mỗi dự án. Video lưu ở `output/videos/<thư mục dự án>/<mã>.mp4` (làm lại → `<mã>_v2.mp4`, không ghi đè).
   - 429 / quota: đưa clip lại hàng đợi, nghỉ 60s, không tính lần thử.
   - 401/403, model chưa mở, thiếu API key: tạm dừng dự án kèm lý do.
   - 5xx / mạng: thử lại tới *số lần thử*; task đã tạo thì chỉ theo dõi tiếp, không gửi lại (không tốn tiền 2 lần).
   - Nội dung bị từ chối / tham số sai: lỗi ngay, hiện lý do.
   - Backend khởi động lại giữa chừng: clip đang tạo quay về hàng đợi, giữ task ID; dự án tạm dừng chờ bấm Chạy.
4. **Làm lại** theo mã (`005, 012-015, 019A`) kèm ghi chú sửa (ghép vào cuối prompt), sửa prompt từng clip, dừng clip, chạy lại clip lỗi.

API: `/api/v1/script-projects` (list, import, `{id}`, `start`, `pause`, `retry-failed`, `redo`, `clips?status=&q=`,
`references`, `references/{code}`, `references/{code}/upload`, `references/auto-match`, `references/generate`,
`references/stop-generate`, `references/{code}/prompt`, `references/match-files`, `references/{code}/approve`, `references/{code}/reject`, `references/approve-all`) và `/api/v1/script-clips/{id}` (`PUT`, `run`, `cancel`).

## Nguồn tạo video BOTSD (botsd.pro)

Ngoài BytePlus, Kịch bản hàng loạt chạy được qua API BOTSD (tính bằng Point BOTSD):
1. **Cài đặt › BOTSD**: nhập tên đăng nhập + mật khẩu → **Đăng nhập** (xem số dư, bảng giá). Mật khẩu và `clientToken` chỉ lưu ở
   `config.properties` (`botsd_username`, `botsd_password`, `botsd_token`); phiên hết hạn (401) thì app tự đăng nhập lại.
2. **Kịch bản › Cấu hình chạy › Nguồn tạo video = BOTSD**, chọn model `sd20` / `sd25` / `sd25hd` / `muse`, độ phân giải (original / 1080p / 4k),
   chế độ người thật (tắt / cơ bản / chuyên sâu). Tỉ lệ: 16:9, 9:16, 1:1, 4:3, 3:4, 21:9 (Muse AI chỉ 16:9 / 9:16, tối đa 1 ảnh).
3. Bấm Chạy như bình thường. Mỗi clip: ảnh tham chiếu trên máy gửi base64 đúng thứ tự (nhận dạng jpg/png/webp theo nội dung file,
   không cần Virtual Portrait) → `POST /api/web/create` → xem tiến độ mỗi 15s → `videoReady` thì tải về `output/videos/<dự án>/<mã>.mp4`.
   Thời lượng clip làm tròn lên 5 / 10 / 15 / 30s. Task ID lưu dạng `botsd:web_…` (backend khởi động lại thì theo dõi tiếp, không gửi lại).

Xử lý lỗi: 402 hết Point / 401 sai tài khoản → tạm dừng dự án; 429 → chờ `retryAfterSeconds`; 409 (quá số task đang chạy) → chờ 30s;
503 / 5xx / mạng → thử lại; 400 → lỗi clip; task `failed` → tạo task mới (tính 1 lần thử). Gửi task lần lượt: mất kết nối lúc gửi thì app
so lịch sử BOTSD trước/sau để biết task đã được nhận chưa – không chắc thì tạm dừng dự án chứ không tự gửi lại (tránh trừ Point 2 lần).
BOTSD không có lệnh huỷ: dừng clip chỉ ngừng theo dõi.

API: `POST /api/v1/settings/botsd/login`, `GET /api/v1/settings/botsd/status`, `DELETE /api/v1/settings/botsd`.

## REST API của backend

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/v1/models` | danh sách model ảnh/video, chế độ video, tỉ lệ |
| GET / PUT | `/api/v1/settings` | cài đặt (secret trả về dạng che) |
| POST | `/api/v1/settings/connection-test` | kiểm tra API key và AK/SK |
| POST | `/api/v1/images/generations` | tạo ảnh, trả về **202** kèm job |
| POST | `/api/v1/videos/generations` | tạo video, trả về **202** kèm job (tạo task, theo dõi, tải về, tự đẩy VP) |
| GET | `/api/v1/jobs?page&size`, `/api/v1/jobs/{id}` | lịch sử job (lưu DB), log, file kết quả |
| POST | `/api/v1/jobs/{id}/cancel` | dừng theo dõi |
| GET | `/api/v1/video-tasks?page&size&status` | danh sách task Seedance |
| POST | `/api/v1/video-tasks/{id}/downloads` | tải video của task (job) |
| DELETE | `/api/v1/video-tasks/{id}` | huỷ (queued) hoặc xoá bản ghi, trả về 204 |
| GET | `/api/v1/files?kind=IMAGE\|VIDEO&page&size` | thư viện local (phân trang) |
| GET | `/api/v1/files/{id}`, `/api/v1/files/{id}/content` | thông tin / nội dung file (hỗ trợ Range cho video) |
| DELETE | `/api/v1/files/{id}` | xoá file và bản ghi |
| GET / POST | `/api/v1/asset-groups` | danh sách / tạo group Virtual Portrait |
| GET | `/api/v1/assets?group&name&status&page&size` | danh sách asset (phân trang) |
| POST | `/api/v1/assets` `{fileIds:[...]}` | đẩy file thư viện (dùng URL gốc 24h), trả về job |
| POST | `/api/v1/assets/from-url` `{url}` | đẩy từ URL công khai, trả về job |
| POST | `/api/v1/assets/{id}/downloads` | tải asset về thư viện |
| DELETE | `/api/v1/assets/{id}` | xoá asset |

Tham chiếu gửi lên khi tạo ảnh/video có thể là: URL `http(s)`, `data:` URI (file tải lên từ trình duyệt), `asset://ID`, hoặc `file:{id}` (file trong thư viện).
Với dạng `file:{id}`, backend ưu tiên URL gốc khi còn hạn, rồi đến asset nếu có, nếu không thì đọc file và gửi base64.
Lỗi validation trả về 400 dạng `{"error": "...", "code": "VALIDATION", "fields": {"prompt": "Prompt không được để trống"}}`.

## Virtual Portrait

```
Tạo ảnh/video → lưu output\ (URL gốc 24h ghi vào bảng generated_file)
             → CreateAsset(URL gốc) → GetAsset chờ Active → asset://ID làm tham chiếu cho Seedance 2.x
```

Chuẩn bị (làm 1 lần trên console):
1. Kích hoạt **Advanced Creation Rights** cho Seedance.
2. Model Playground › My assets › **Virtual Portrait**: **ký thư uỷ quyền**. Bắt buộc trước khi tạo group đầu tiên.
3. Tạo **Access Key / Secret Key** (IAM). Nên dùng IAM user có quyền `ark:*Asset*`.
4. Trang Cài đặt: nhập AK/SK và **Project**. Project phải trùng project của API key, ví dụ `twen-customer`.

Giới hạn: `CreateAsset` chỉ nhận URL công khai, không nhận file hay base64. Vì vậy chỉ đẩy được file trong vòng 24h sau khi tạo; quá hạn thì dùng "Đẩy từ URL".
Ảnh asset phải có cạnh 300–6000 px và tỉ lệ 0.4–2.5. Asset phải là nhân vật ảo, không giống người thật. Quota thư viện có hạn, nên định kỳ xoá asset không dùng.
Trong prompt gọi tham chiếu theo thứ tự *Image 1, Image 2, Video 1…*. Không ghi asset ID vào prompt.

## Ghi chú API BytePlus (chi tiết ở `docs/api/`)

- Data plane: `https://ark.ap-southeast.bytepluses.com/api/v3`, header `Authorization: Bearer <API key>`.
- Asset (control plane): `POST https://ark.ap-southeast-1.byteplusapi.com/?Action=...&Version=2024-01-01`, ký HMAC-SHA256 bằng AK/SK.
- Seedance 2.x cần kích hoạt model và số dư > 30 USD hoặc gói tài nguyên. Không nhận mặt người thật, trừ asset `asset://` hoặc ảnh do chính tài khoản tạo bằng Seedream 5.0 text-to-image (trong 30 ngày).
- URL ảnh/video kết quả sống 24h. Video Seedance 2.5 tải tối đa 100 lần.

| Video | Độ phân giải | Thời lượng |
|---|---|---|
| `dreamina-seedance-2-5-260628` | 480p / 720p / 1080p | 4–30s, -1 |
| `dreamina-seedance-2-0-260128` | 480p / 720p / 1080p / 4k | 4–15s, -1 |
| `dreamina-seedance-2-0-fast-260128`, `-mini-260615` | 480p / 720p | 4–15s, -1 |
| `seedance-1-5-pro-251215` | 480p / 720p / 1080p | 4–12s, -1 |
| `seedance-1-0-pro-250528`, `-fast-251015` | 480p / 720p / 1080p | 2–12s |

| Ảnh | Kích thước |
|---|---|
| `dola-seedream-5-0-pro-260628`, `dola-seedream-5-0-flash-260915` | 1K / 1.5K / 2K, tối đa 10 tham chiếu |
| `seedream-5-0-260128` (lite) | 2K / 3K / 4K, sequential |
| `seedream-4-5-251128` | 2K / 4K, sequential |
| `seedream-4-0-250828` | 1K / 2K / 4K, sequential |
