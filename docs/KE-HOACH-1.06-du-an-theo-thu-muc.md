# Kế hoạch nâng cấp 1.06 – Kịch bản hàng loạt: **đọc thư mục dự án**, thiếu gì thì tạo

Ngày lập: 26/11/2026 · Bản hiện tại: 1.05 · Trạng thái: **bước 1 xong (1.06); phần chính của bước 2 + 3 xong (1.07)**: tự gán ảnh trong thư mục, ảnh AI vào Ảnh chờ duyệt, video lưu vào Videos/, cảnh có video là Xong. **Bước 4 xong (1.09)**: thanh trên cố định + ô đếm bấm để lọc, dải tổng quan ảnh tham chiếu.

Làm **từng bước một**. Xong mỗi bước, mình đóng gói một bản để bạn thử. Bạn duyệt xong mới làm bước tiếp theo.

---

## 1. Mình hiểu yêu cầu như sau

- Ark và Vidora **cùng cách làm nhưng là 2 app riêng, nền tảng khác nhau**. Không làm chung, không cần tương thích nhau (không đọc `.vidora/`, không chạy chung thư mục). Vidora chỉ là ví dụ về cách làm.
- **Hiện tại ở Kịch bản hàng loạt:** muốn làm dự án nào là phải **nhập file Excel** vào app. App chép dữ liệu sang chỗ riêng (`output/`), nên:
  - không biết trong thư mục dự án đã có ảnh tham chiếu, video nào;
  - Excel sửa xong phải nhập lại.
- **Bạn muốn:** ở Kịch bản hàng loạt chỉ cần **chọn thư mục dự án** (vd `KET QUA - IDE 21`). App sẽ:
  1. **Quét dự án:** tự đọc các file Excel có sẵn trong thư mục (cảnh, ảnh tham chiếu, thoại). **Không phải chọn thêm / nhập thêm file Excel nào.**
  2. **Đọc ảnh tham chiếu đã có** trong `Ảnh tham chiếu/`. Mã nào **chưa có ảnh thì tạo** (AI), lưu ngay vào thư mục dự án.
  3. **Đọc video đã có** trong `Videos/`. Cảnh nào **chưa có video thì tạo**, lưu vào `Videos/` của dự án. Cảnh đã có video thì không làm lại.
  4. Bấm **Quét lại** bất cứ lúc nào: Excel vừa sửa, ảnh / video vừa bỏ thêm vào thì app cập nhật ngay.

Ví dụ với IDEA 21 (đã kiểm tra):
- Có 70 ảnh trong `Ảnh tham chiếu/` → không tạo lại.
- Có 55 file video (45 cảnh) trong `Videos/` → 45 cảnh hiện **Xong**, chỉ làm 165 cảnh còn lại.

Phần đang có của Ark vẫn giữ: 3 nguồn video (BytePlus / BOTSD / 888API), Virtual Portrait, tạo ảnh AI + duyệt ảnh, phân tích chặn.
Thư viện của dự án = thư mục dự án, nên 2 dự án trùng mã không bao giờ lẫn ảnh.

---

## 2. App đọc / ghi gì trong thư mục dự án

| Trong thư mục dự án | App làm gì | Ghi chú |
|---|---|---|
| Excel cảnh (vd `all-video-prompts.xlsx`, `KICH BAN … .xlsx`) | **Đọc** danh sách cảnh, prompt, mã ảnh, thời lượng. **Bắt buộc** | Nhận theo tên cột bên trong, không theo tên file. Có 2 file cùng loại thì hỏi chọn |
| Excel ảnh tham chiếu (`reference-prompts.xlsx`) | **Đọc** mã ảnh, tên, prompt tạo ảnh | Không bắt buộc |
| `voice-overlay.xlsx` | **Đọc** lời thoại, ghi vào ghi chú clip | Không bắt buộc |
| `Ảnh tham chiếu/` | **Đọc** ảnh đã có (tên file = mã). Ảnh đã duyệt được **ghi** vào đây | Chưa có thì app tạo. Bỏ qua thư mục con `_cu/`, `_bo/` |
| `Ảnh chờ duyệt/` | Ảnh AI mới tạo được **ghi** vào đây, chờ bạn Duyệt | Chưa có thì app tạo |
| `Videos/` | **Đọc** để biết cảnh nào đã xong. Video mới được **ghi** vào đây | Chưa có thì app tạo |
| `.ark/` *(ẩn)* | Cài đặt dự án (nguồn video, model…), nhật ký chạy | Của riêng Ark |

**Quy tắc an toàn:**
- Không xoá, không ghi đè file nào. Ảnh cũ bị thay thì cất vào `_cu/`. Video làm lại thì đánh số.
- Không tự làm lại cảnh đã có video. Chỉ làm lại khi bạn bấm.
- Không tự tạo ảnh / video, không tự tiêu tiền ngoài lệnh bạn bấm.
- **File Excel chỉ đọc**, app không sửa.

---

## 3. Các bước (mỗi bước một bản, bạn thử xong mới sang bước sau)

### Bước 1 – Chọn thư mục dự án và quét (bản 1.06)
- [x] Nút **"Mở thư mục dự án"** thay cho "Nhập Excel":
  - hộp chọn thư mục của Windows, hoặc dán đường dẫn;
  - danh sách **Dự án gần đây** để mở lại nhanh.
- [x] **Quét:** đọc thẳng các file Excel trong thư mục (không tải lên, không chép). Dùng bộ đọc đã có, hiểu được cả mẫu IDEA 21 lẫn S11_13.
- [x] **Bảng kết quả quét:**
  - Liệt kê file cảnh, file ảnh tham chiếu, file thoại, các thư mục ảnh / video, kèm số lượng (vd "210 cảnh · 70 mã ảnh · 45 cảnh đã có video").
  - Thiếu Excel cảnh thì **tô đỏ** và khoá nút Chạy.
- [x] Nút **Quét lại**: đọc lại Excel và thư mục. Prompt / mã ảnh thay đổi thì cập nhật, cảnh đang chạy không bị ảnh hưởng.
- [x] Nút **Mở thư mục** (mở bằng Explorer).
- [x] Cài đặt dự án lưu ở `<dự án>/.ark/`, mở lại thì có lại đủ.
- [x] Dự án nhập Excel kiểu cũ vẫn dùng được như trước. Danh sách dự án ghi rõ loại nào là "thư mục", loại nào là "nhập Excel".

### Bước 2 – Ảnh tham chiếu: đọc cái đã có, tạo cái còn thiếu (bản 1.07)
- [x] **Đọc ảnh có sẵn** trong `Ảnh tham chiếu/`:
  - Tên file trùng mã, nhận cả `CHAR-X-1.jpg` lẫn `CHAR_X_01.png`.
  - Một mã có nhiều ảnh thì dùng ảnh mới nhất, đổi được bằng tay.
- [x] Mã chưa có ảnh hiện **viền đỏ**. Nút **"Tạo bằng AI các ảnh còn thiếu"** chỉ tạo cho mã chưa có ảnh (ảnh đang chờ duyệt cũng tính là có).
- [x] **Ảnh AI mới:**
  - Lưu vào `Ảnh chờ duyệt/<MÃ>.png`.
  - **Duyệt:** chuyển sang `Ảnh tham chiếu/`, ảnh cũ (nếu có) cất vào `_cu/`.
  - **Bỏ:** ảnh vào `_bo/`.
  - Ô "Tự duyệt ảnh AI" vẫn giữ.
- [x] **Tải lên / chọn ảnh từ máy** cho một mã: chép vào `Ảnh tham chiếu/<MÃ>.<đuôi>`, không ghi đè.
- [x] Bạn tự bỏ ảnh vào `Ảnh tham chiếu/` rồi bấm Quét lại thì app nhận ngay.
- [x] Cách gửi ảnh của bản 1.05 giữ nguyên: BytePlus đẩy Virtual Portrait vào group riêng của dự án, BOTSD gửi file, 888API gửi link tạm.

### Bước 3 – Video: đọc cái đã có, tạo cái còn thiếu (bản 1.08)
- [x] **Đọc `Videos/`**: cảnh có file video trùng số cảnh (`001.mp4`, `001_(1).mp4`, `001_v2.mp4`…) là **Xong**.
- [x] **Trạng thái mỗi cảnh:** Xong · Thiếu ảnh · Sẵn sàng · Đang chạy · Lỗi.
- [x] **Chạy** chỉ gửi cảnh *Sẵn sàng*, tức là chưa có video và đủ ảnh.
- [x] **Lưu video mới** vào `<dự án>/Videos/NNN.mp4`. Làm lại thì `NNN_v2.mp4`, `NNN_v3.mp4`…, không ghi đè.
- [x] **Tắt app rồi mở lại** thì chạy tiếp: task đã gửi được nhớ, chỉ theo dõi tiếp, không gửi lại.
- [x] **Làm lại** chỉ khi bấm: "Làm lại cảnh này", "Làm lại các cảnh lỗi", "Làm lại các cảnh đã chọn".
- [x] Kiểm tra với IDEA 21: 45 cảnh đã có video phải hiện Xong và không bị gửi lại.

### Bước 4 – Màn hình dự án gọn hơn (bản 1.09)
- [x] **Phần trên cố định:**
  - tên và đường dẫn dự án;
  - các ô đếm **Xong / Sẵn sàng / Thiếu ảnh / Lỗi / Đang chạy / Ảnh thiếu / Chờ duyệt**, bấm vào ô nào thì lọc theo ô đó;
  - nút Chạy / Tạm dừng / Quét lại.
- [x] **Dải tổng quan ảnh tham chiếu:**
  - Có dòng tóm tắt "64/70 đã có · 6 thiếu · 2 chờ duyệt".
  - Chia hàng theo Nhân vật / Bối cảnh / Đạo cụ, ô thiếu màu đỏ xếp đầu.
  - Bấm vào ô thì nhảy tới thẻ ảnh.
- [x] **Chỉ danh sách cuộn**, phần trên đứng yên. Mở dự án thì bộ lọc về "Tất cả".

### Bước 5 – Sửa prompt (bản 1.10, tuỳ chọn)
- [ ] Prompt cảnh / prompt ảnh sửa trong app thì lưu ở `.ark/`, Excel gốc không đổi. Quét lại không làm mất bản đã sửa.
- [ ] (Tuỳ chọn) Nút "Ghi vào Excel": sao lưu file gốc trước rồi mới ghi.

---

## 4. Cần bạn chốt (không ý kiến thì mình làm theo cột bên phải)

| Câu hỏi | Mặc định |
|---|---|
| Ảnh và video mới lưu ở đâu | **Trong thư mục dự án** (`Ảnh chờ duyệt/`, `Ảnh tham chiếu/`, `Videos/`) |
| Tên video mới | `NNN.mp4`; làm lại thì `NNN_v2.mp4`… Đọc thì nhận mọi kiểu `NNN*.mp4` đã có |
| Ảnh AI có phải bấm Duyệt mới dùng | Có (ô "Tự duyệt ảnh AI" vẫn giữ) |
| Dự án nhập Excel kiểu cũ | Giữ nguyên, vẫn chạy được |
| Bước 5 | Làm sau cùng, khi bạn cần |

---

## 5. Kiểm thử mỗi bước
- **IDEA 21 thật:** chỉ quét và hiển thị, không tạo gì, không tốn tiền. Kiểm tra: 210 cảnh, 70 mã ảnh có ảnh, 45 cảnh hiện Xong.
- **Bản sao của IDEA 21** ở thư mục tạm, xoá bớt vài ảnh / video:
  - App phải báo đúng phần thiếu.
  - Chỉ tạo phần thiếu (BOTSD / 888API dùng server giả).
  - Ảnh lưu đúng chỗ, video lưu đúng `Videos/NNN.mp4`, không file nào bị ghi đè hay xoá.
- **Test tự động** cho phần quét thư mục, nhận tên file ảnh / video và trạng thái cảnh.
- **Mỗi bước:** đóng gói bản mới, commit (người viết `nguyenhuyducbg19062002-hash`, không ghi đồng tác giả), tag, push.
