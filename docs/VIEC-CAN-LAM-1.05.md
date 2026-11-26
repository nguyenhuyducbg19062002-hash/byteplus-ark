# Việc cần làm – bản 1.05

> Trạng thái 26/11/2026: đã làm các mục đánh dấu [x] trong bản 1.05. Còn lại: B4, B5.1, B5.2, C2, C4 (C2 không cần vì thư viện đã làm trống).

Ngày lập: 26/11/2026. Bản hiện tại 1.04. Tài liệu đã đọc: BytePlus ModelArk (theo code hiện có), BOTSD
(`BOTSD_API_Huong_dan_Tieng_Viet_2026.docx`), 888API (https://888api.xin/docs và /docs-next).
Chi tiết thiết kế phần thư viện: [KE-HOACH-1.05-thu-vien-theo-du-an.md](KE-HOACH-1.05-thu-vien-theo-du-an.md).

Gồm 2 phần:
- **A.** Thư viện ảnh riêng cho từng dự án.
- **B.** Mỗi nguồn video (BytePlus / BOTSD / 888API) chỉ hiện tính năng mà API đó có, ẩn tính năng nó không có.

Các mục ghi **❓ Cần chốt** là chỗ bạn cần chọn trước khi mình làm.

---

## A. Thư viện ảnh riêng cho từng dự án

Nguyên nhân bị lẫn (đã kiểm tra trong code):
- Mỗi lần nhập Excel, app **tự chạy "Gán tự động"** và tìm ảnh trùng tên mã trên **toàn bộ thư viện**.
- BytePlus tự ghép asset Virtual Portrait theo tên mã trên cả tài khoản, mà mọi dự án lại dùng chung một asset group.

- [x] **A1.** Mỗi file thư viện ghi rõ thuộc dự án nào (`projectId`). Các chỗ tạo file cho dự án đều ghi vào: tải ảnh lên, nhập thư mục, ảnh AI, ảnh chờ duyệt, asset VP tải về, video kết quả.
- [x] **A2.** *(1.05: thư viện đã được làm trống và sao lưu sang output_saoluu_26-11 trước khi nâng cấp; vẫn có bước tự nhận dự án theo thư mục cho file cũ)* Chuyển dữ liệu cũ (tự chạy một lần):
  - File nằm trong `assets/<thư mục dự án>` hoặc `videos/<thư mục dự án>` thì thuộc dự án đó.
  - File chỉ một dự án dùng thì thuộc dự án đó.
  - File đang dùng chung nhiều dự án → **❓ Cần chốt**:
    - (1) Tự sao chép thành bản riêng cho từng dự án. **Mình đề xuất cách này.**
    - (2) Chỉ liệt kê ra để bạn tự chọn lại.
- [x] **A3.** "Gán tự động" (cả lần tự chạy khi nhập Excel) chỉ tìm ảnh của chính dự án. Dự án chưa có ảnh thì báo thiếu, không lấy ảnh dự án khác.
- [x] **A4.** Ô chọn ảnh từ thư viện:
  - Mặc định chỉ hiện ảnh của dự án đang mở, có công tắc "Xem tất cả dự án".
  - Chọn ảnh của dự án khác thì app sao chép vào dự án đang mở rồi mới gán.
- [x] **A5.** Trước khi gửi clip (cả 3 nguồn): nếu mã trỏ tới ảnh của dự án khác thì không gửi và báo rõ mã nào, thuộc dự án nào.
- [x] **A6.** Kiểu lưu "Thư mục riêng của tôi": tự tạo thư mục con theo tên dự án.
- [x] **A7.** Trang Thư viện thêm bộ lọc theo dự án. Tab Ảnh tham chiếu hiện nhãn dự án và cảnh báo đỏ nếu ảnh không thuộc dự án.
- [x] **A8.** Báo cáo sau khi cập nhật: liệt kê các mã đang dùng ảnh của dự án khác để bạn kiểm tra.
- [x] **A9. Chỉ BytePlus:**
  - Mỗi dự án có asset group Virtual Portrait riêng, app tự tạo, đặt theo tên dự án.
  - Khi ghép asset theo tên mã, app chỉ nhận asset của group dự án, hoặc asset bạn tự tải lên console mà không thuộc dự án nào. Không bao giờ nhận asset của dự án khác.
  - Nếu không tạo được group thì dùng group chung, đặt tên asset `<dự án>__<mã>`.
- [x] **A10.** Xoá dự án: hỏi thêm có xoá ảnh của dự án không (mặc định **không**). Với BytePlus hỏi thêm có xoá asset group VP không.

---

## B. Tính năng theo từng nguồn video

### B0. Bảng so sánh (theo tài liệu từng API)

| Tính năng | BytePlus | BOTSD | 888API |
|---|---|---|---|
| Model | Seedance 2.5 / 2.0 / 2.0 fast / 2.0 mini / 1.5 pro / 1.0 pro… | `sd20`, `sd25`, `sd25hd`, `muse` | Theo danh sách model (khoảng 15 model Seedance trong app, gõ được mã khác) |
| Thời lượng | Theo model: 2.0 là 4–15s, 2.5 là 4–30s | Chỉ 5 / 10 / 15 / 30s (muse 5 / 10s), app làm tròn lên | Theo model: 4–15s, 4–30s, có model chỉ 10 / 15 hoặc 15s |
| Độ phân giải | Chọn theo model: 480p / 720p / 1080p / 4k | `original` / `1080p` / `4k` (sd25hd nên để original) | **Cố định theo model** (720p / 1080p…), không chọn được |
| Tỉ lệ | 6 tỉ lệ + `adaptive` | 6 tỉ lệ, **không có adaptive**; muse chỉ 16:9 / 9:16 | Theo model (3, 4 hoặc 6 tỉ lệ), **không có adaptive** |
| Tạo âm thanh (`generate_audio`) | **Có** | **Không có** tham số | **Không có** trong tài liệu |
| Số ảnh tham chiếu tối đa | Theo model (9 hoặc 30) | 30 (sd25 / sd25hd), muse 1 ảnh khung đầu; sd20 tài liệu không ghi | Theo model (9, 10 hoặc 30) |
| Video / âm thanh tham chiếu | Có (2.0: 3 / 3, 2.5: 10 / 10) | Chỉ dòng 2.5: tối đa 10 video (≤30s, **tính thêm Point**) + 10 âm thanh | Theo model (vd 3 / 3, 10 / 10, hoặc không có) |
| Ảnh có mặt người thật | Phải đẩy **Virtual Portrait** (`asset://`) | Cờ **Chế độ người thật** cơ bản / chuyên sâu (cần ≥1 ảnh, muse không có) | Không có cờ. **Chọn model** nhận người thật / mặt người gốc |
| Cách gửi ảnh | `asset://`, URL còn hạn hoặc base64 | base64 (jpg / png / webp ≤30MB) | **Chỉ link HTTPS** (TOS / Litterbox). Model `-face` chỉ PNG / JPEG ≤10MB |
| Độ dài prompt tối đa | Theo BytePlus | 15.000 (sd25 / sd25hd), 20.000 (khác) | Theo model (5.000 / 8.000 / 10.000 / 45.000…) |
| Huỷ task | **Có** (xoá task) | **Không có** | **Không có** |
| Kiểm duyệt video đầu ra | Có mã lỗi riêng → app tự tạo lại task mới | Chỉ báo `failed` + message | Chỉ báo `failed` + error / message |
| Số dư / giá | Không xem trong app | Point + bảng giá `videoPricing` | Số dư CNY + giá theo model |
| Gửi song song | Giới hạn tốc độ (429) | Giới hạn số task đang chạy (409), app gửi lần lượt | Có 429 (`Retry-After`). Mất kết nối lúc gửi thì **không được tự gửi lại** |

Phần **dùng BytePlus bất kể chọn nguồn video nào** (cần API key BytePlus):
- Tạo ảnh tham chiếu bằng AI (Seedream).
- AI viết prompt ảnh, dịch prompt.
- "Phân tích chặn".

### B1. Cấu hình chạy: hiện / ẩn theo nguồn

- [x] **B1.1. Thời lượng:** hiện dòng "Clip X s sẽ gửi Y s" theo model đang chọn.
  - BOTSD: làm tròn lên.
  - 888API / BytePlus: kẹp trong khoảng của model.
  - Có cảnh báo khi clip bị cắt ngắn.
- [x] **B1.2. Độ phân giải:**
  - BytePlus: chọn theo model.
  - BOTSD: original / 1080p / 4k.
  - 888API: chỉ hiện chữ (cố định theo model), không cho chọn.
- [x] **B1.3. Tỉ lệ:** danh sách lọc theo nguồn và model. `adaptive` chỉ có ở BytePlus; muse chỉ 16:9 / 9:16; 888API chỉ hiện các tỉ lệ model nhận. Đổi model mà tỉ lệ cũ không hợp lệ thì tự chuyển về 16:9 và báo.
- [x] **B1.4. "Tạo âm thanh":** chỉ hiện với BytePlus. Ẩn với BOTSD và 888API.
- [x] **B1.5. "Chế độ người thật":** chỉ hiện với BOTSD (ẩn với muse).
  - 888API: hiện nhãn "nhận người thật / mặt người gốc" cạnh từng model.
  - BytePlus: hiện dòng "ảnh nhân vật được đẩy lên Virtual Portrait".
- [x] **B1.6.** Model BytePlus và độ phân giải BytePlus chỉ hiện khi chọn BytePlus (đã làm ở 1.03 / 1.04, rà lại).
- [x] **B1.7. Thông tin số dư / giá:**
  - BOTSD: số dư Point và giá ước tính mỗi clip theo số giây.
  - 888API: số dư và giá của model.
  - BytePlus: không hiện.
- [x] **B1.8. Gợi ý số clip chạy song song:**
  - BOTSD: tối đa theo giới hạn tài khoản, gặp 409 thì tự chờ.
  - 888API: đề xuất 3.
  - BytePlus: như cũ.

### B2. Tab Ảnh tham chiếu: hiện / ẩn theo nguồn

- [x] **B2.1.** Nút "Đẩy nhân vật lên Virtual Portrait", "Ghép asset VP" và cảnh báo "ảnh bị chặn mặt người" **chỉ hiện với BytePlus**.
- [x] **B2.2. Kiểm tra ảnh theo nguồn:**
  - BOTSD: jpg / png / webp ≤30MB.
  - 888API: model `-face` chỉ PNG / JPEG ≤10MB.
  - Clip có nhiều ảnh hơn mức model cho phép thì báo ngay ở danh sách clip, không đợi tới lúc gửi.
- [x] **B2.3. 888API:** hiện chỗ để ảnh tạm đang dùng (TOS / Litterbox). Nếu chưa sẵn sàng (thiếu AK/SK, TOS chưa bật) thì báo ngay ở tab này.
- [x] **B2.4.** Nút "Tạo ảnh AI" và "AI viết prompt" cần key BytePlus. Không có key thì làm mờ nút và ghi lý do, thay vì báo lỗi khi bấm.

### B3. Danh sách clip / chạy clip

- [x] **B3.1. Nút dừng clip:**
  - BytePlus: "Huỷ" (xoá task).
  - BOTSD / 888API: đổi thành **"Ngừng theo dõi"**, kèm nhắc "task vẫn chạy và vẫn tính tiền trên máy chủ".
- [x] **B3.2.** Clip lỗi: nút "Phân tích chặn" và "Dùng prompt đã sửa" chỉ hiện khi có key BytePlus. Lỗi của BOTSD / 888API hiện nguyên văn thông báo máy chủ.
- [x] **B3.3.** Mỗi clip hiện nhãn nguồn đã gửi (BytePlus / BOTSD / 888API, theo tiền tố mã task) để đổi nguồn giữa chừng vẫn rõ clip nào chạy ở đâu.
- [x] **B3.4. Bộ đếm ký tự prompt:** giới hạn theo nguồn và model (BOTSD 15k / 20k, 888API theo model). Vượt giới hạn thì tô đỏ.

### B4. Video / âm thanh tham chiếu

- [ ] **B4. (để bản sau)** hiện kịch bản chỉ gửi **ảnh**. Cả 3 API đều nhận thêm video / âm thanh tham chiếu (BOTSD chỉ dòng 2.5, và video bị tính thêm Point). Hai lựa chọn:
  - (1) Để sang bản sau. **Mình đề xuất cách này.**
  - (2) Làm luôn ở 1.05: thêm cột "video / âm thanh tham chiếu" cho clip, kèm giới hạn theo từng nguồn.

### B5. Trang Cài đặt

- [ ] **B5.1.** Mỗi nguồn một thẻ (BytePlus / BOTSD / 888API) có trạng thái "sẵn sàng / chưa".
  - 888API: kiểm tra luôn chỗ để ảnh (TOS dùng được chưa).
- [ ] **B5.2. (để bản sau)** 888API cũng có model tạo ảnh (seedream-5.5, gpt-image-2…). Có cần cho chọn "tạo ảnh tham chiếu bằng 888API" khi không có key BytePlus không? Mình đề xuất để sang bản sau.

---

## C. Kiểm thử và phát hành

- [x] **C1.** Hai dự án trùng mã `CHAR_BRA_01`, ảnh khác nhau. Kiểm tra gán tự động, chọn từ thư viện và chạy clip ở cả 3 nguồn (BOTSD và 888API dùng server giả, BytePlus dùng group VP riêng): mỗi dự án đúng ảnh của mình.
- [ ] **C2.** Dữ liệu cũ đang dùng chung ảnh: sau khi chuyển, mỗi dự án đúng ảnh, clip vẫn chạy.
- [x] **C3.** Đổi nguồn qua lại: các mục hiện / ẩn đúng bảng B0, tỉ lệ / thời lượng không hợp lệ được báo trước khi chạy.
- [ ] **C4.** Khởi động lại backend khi clip đang chạy ở mỗi nguồn: theo dõi tiếp, không gửi lại.
- [x] **C5.** Test tự động, build app, cài vào máy, commit (người viết `nguyenhuyducbg19062002-hash`, không ghi đồng tác giả), tag **v1.05**, push.

## Thứ tự làm đề xuất

A1 → A2 → A3 → A5 → A4 → A9 → B1 → B2 → B3 → A6 / A7 / A8 / A10 → B5 → C.
Phần A làm trước vì đó là lỗi lấy nhầm ảnh. Phần B chủ yếu là giao diện.
