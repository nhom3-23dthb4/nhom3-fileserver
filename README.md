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
| MSSV | Ho va Ten | Cong viec da thuc hien | Merge Requests |
|------|-----------|------------------------|----------------|
|2380600819|Lâm Tấn Huy|                    |                | 
|2380601533|Phạm Hữu Nhân|                  |                |
|2380601249|Nguyễn Thành Long|              |                |
|2380601593|Huỳnh Minh Nhựt|                |                |