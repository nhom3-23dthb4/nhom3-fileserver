# File Server co Xac thuc & Uy quyen (De tai 5)

## Chay thu (phan A, B/C dang la ban dev tam trong package `dev`)
```bash
export FS_KEYSTORE_PASSWORD=changeit
./certs/gen-keystore.sh          # tao certs/server.p12 (khong commit)
mvn test                         # chay bo test cua A
mvn -q compile exec:java -Dexec.mainClass=vn.edu.fs.server.ServerMain
```
Tai khoan dev: alice/alice123 (toan quyen), bob/bob123 (LIST/READ, bi chan /secret).

## Bang dong gop
| MSSV | Họ và Tên | Công việc đã thực hiện | Merge Requests |
| --- | --- | --- | --- |
| 2380600819 | Lâm Tấn Huy | A1: Khởi tạo repo, .gitignore, khóa nhánh, README<br>A2: Gói common (Frame, FrameIO, ErrorCode, interface)<br>A3: TlsConfig và FileServer (TLS 1.3, thread pool)<br>A4: ClientHandler và CommandDispatcher<br>A5: AuditLogger<br>A6: Timeout, giới hạn khung, xử lý ngắt kết nối<br>A7: Tích hợp cuối, thay lớp dev bằng module thật<br>D1: FileClient (kết nối TLS, API lệnh)<br>Bổ sung: Tự đăng nhập lại khi mất kết nối (#58)<br>Bổ sung: Tiếp tục tải dở / resume cho READ/WRITE (#60) | !26, !27, !57, !59, !62 |
| 2380601533 | Phạm Hữu Nhân | B1: Thiết kế mô hình user, group, rule ACL<br>B2: PasswordHasher PBKDF2 và UserStore<br>B3: SessionManager và AuthService<br>B4: AclStore và AclService.isAllowed<br>B5: Test ACL (ít nhất 20 tình huống)<br>B6: Lệnh quản trị và tải lại ACL<br>C1: PathSandbox chống Path Traversal<br>C2: FileService (list, mkdir, delete) | !39, !40, !41, !42, !43, !44, !45, !46 |
| 2380601249 | Nguyễn Thành Long | C3: ChunkTransfer (đọc/ghi theo khối, file .part)<br>C4: LockManager (khóa Read/Write theo file)<br>C5: Kiểm tra SHA-256 và giới hạn dung lượng<br>C6: Test traversal, file 500 MB, xung đột ghi<br>D5: Test tải 20 client, đo tốc độ và bộ nhớ | !47, !49, !50, !51, !52 |
| 2380601593 | Huỳnh Minh Nhựt | D2: Màn hình đăng nhập và cấu hình host/port<br>D3: Màn hình duyệt thư mục, tải lên/xuống, hiển thị DENY<br>D4: Test tích hợp end-to-end và test bảo mật<br>D6: Báo cáo, sơ đồ giao thức, slide, kịch bản demo<br>D7a: SecurityTest.java | !54, !55, !56 |

> Ghi chú: một Merge Request có thể bao gồm nhiều Issue nên số MR không tương ứng 1-1 với số công việc. Chi tiết xem lịch sử commit của từng MR.

- Server giu `.part` khi ket noi dut giua chung; chi xoa khi sai SHA-256 / vuot gioi han.
- Client `upload` tu hoi `PARTSIZE` roi gui phan con lai; `download` ghi vao `<dich>.part` va chi doi ten khi dung SHA-256.
- `.part` khong khop file nguon (file da doi / hong) -> client tu dong gui lai tu dau 1 lan.
