package vn.edu.fs.gui;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import vn.edu.fs.client.FileClient;
import vn.edu.fs.common.ChunkListener;
import vn.edu.fs.common.FileInfo;

public class MainFrame extends JFrame {

    private final FileClient client;
    private final DefaultTableModel model =
            new DefaultTableModel(new Object[]{"Tên", "Loại", "Kích thước"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTable table = new JTable(model);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JLabel status = new JLabel(" ");

    private final JButton upload = new JButton("Tải lên");
    private final JButton download = new JButton("Tải xuống");
    private final JButton mkdir = new JButton("Tạo thư mục");
    private final JButton delete = new JButton("Xóa");
    private final JButton refresh = new JButton("Làm mới");
    private final JButton up = new JButton("Lên");
    private final JButton logout = new JButton("Đăng xuất");

    private String cwd = "/";                                   // thư mục đang xem (đường dẫn ảo của server)
    private java.util.List<FileRow> rows = new ArrayList<>();
    private boolean busy = false;                               // đang có một việc mạng chạy (chỉ dùng ở luồng giao diện)

    /** Một dòng của bảng. */
    private static final class FileRow {
        final String name;
        final boolean dir;
        final long size;
        FileRow(String name, boolean dir, long size) { this.name = name; this.dir = dir; this.size = size; }
    }

    public MainFrame(FileClient client, String userName) {
        super("File Server – " + userName);
        this.client = client;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(720, 480);
        setLocationRelativeTo(null);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        for (JButton b : new JButton[]{refresh, up, upload, download, mkdir, delete}) {
            bar.add(b);
        }
        bar.add(Box.createHorizontalGlue());
        bar.add(logout);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        progress.setStringPainted(true);
        JPanel south = new JPanel(new BorderLayout(8, 0));
        south.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        south.add(status, BorderLayout.CENTER);
        south.add(progress, BorderLayout.EAST);

        add(bar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        logout.addActionListener(e -> doLogout());
        refresh.addActionListener(e -> doRefresh());
        upload.addActionListener(e -> doUpload());
        download.addActionListener(e -> doDownload());
        mkdir.addActionListener(e -> doMkdir());
        delete.addActionListener(e -> doDelete());

        // Nhấp đúp vào thư mục để đi vào
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && !busy) {
                    FileRow r = selectedRow();
                    if (r != null && r.dir) {
                        navigate(join(cwd, r.name));
                    }
                }
            }
        });

        // Nút "Lên": về thư mục cha
        up.addActionListener(e -> {
            if (!"/".equals(cwd)) {
                int i = cwd.lastIndexOf('/');
                navigate(i <= 0 ? "/" : cwd.substring(0, i));
            }
        });

        status.setText("Sẵn sàng");
        doRefresh();                                            // tải danh sách ngay khi mở màn hình
    }

    // ---------------------------------------------------------------- hạ tầng dùng chung

    /** Chạy một việc mạng ở luồng nền; xong thì xử lý kết quả ở luồng giao diện. */
    private <T> void runAsync(String busyText, java.util.concurrent.Callable<T> job,
                              java.util.function.Consumer<T> onOk) {
        if (busy) return;                       // FileClient dùng một socket: không chạy hai việc cùng lúc
        busy = true;
        setButtonsEnabled(false);
        status.setText(busyText);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception {
                return job.call();
            }
            @Override protected void done() {
                busy = false;
                setButtonsEnabled(true);
                try {
                    T result = get();
                    status.setText("Sẵn sàng");
                    if (onOk != null) onOk.accept(result);
                } catch (Exception ex) {
                    status.setText("Lỗi");
                    JOptionPane.showMessageDialog(MainFrame.this, Errors.message(ex),
                            "Thông báo", JOptionPane.WARNING_MESSAGE);
                } finally {
                    progress.setValue(0);
                }
            }
        }.execute();
    }

    private void setButtonsEnabled(boolean on) {
        for (JButton b : new JButton[]{refresh, up, upload, download, mkdir, delete, logout}) b.setEnabled(on);
    }

    /** Listener tiến trình: gọi từ luồng nền, cập nhật thanh ở luồng giao diện. */
    private ChunkListener progressListener() {
        return (done, total) -> {
            final int pct = total <= 0 ? 0 : (int) Math.min(100, done * 100 / total);
            SwingUtilities.invokeLater(() -> progress.setValue(pct));
        };
    }

    private String join(String dir, String name) {
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }

    private FileRow selectedRow() {
        int i = table.getSelectedRow();
        return i < 0 ? null : rows.get(table.convertRowIndexToModel(i));
    }

    private static String humanSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / (1024.0 * 1024.0));
    }

    // ---------------------------------------------------------------- danh sách

    private void doRefresh() {
        navigate(cwd);
    }

    /** Xem thư mục dir. Chỉ đổi cwd khi server trả danh sách thành công (lỗi 403/404 thì ở lại thư mục cũ). */
    private void navigate(final String dir) {
        runAsync("Đang tải danh sách...", () -> {
            java.util.List<FileRow> list = new ArrayList<>();
            for (FileInfo f : client.list(dir)) {
                list.add(new FileRow(f.name(), f.directory(), f.size()));
            }
            // thư mục lên trước, sau đó theo tên
            list.sort(Comparator.<FileRow, Boolean>comparing(r -> !r.dir)
                    .thenComparing(r -> r.name.toLowerCase()));
            return list;
        }, list -> {
            cwd = dir;
            rows = list;
            model.setRowCount(0);
            for (FileRow r : rows) {
                model.addRow(new Object[]{r.name, r.dir ? "Thư mục" : "File", r.dir ? "" : humanSize(r.size)});
            }
            setTitle("File Server – " + cwd);
        });
    }

    // ---------------------------------------------------------------- tải lên / tải xuống

    private void doUpload() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final java.io.File f = fc.getSelectedFile();
        final String remote = join(cwd, f.getName());
        runAsync("Đang tải lên " + f.getName() + "...", () -> {
            client.upload(remote, f.toPath(), progressListener());   // chú ý: (đường dẫn trên server, file nguồn, listener)
            return null;
        }, ok -> { JOptionPane.showMessageDialog(this, "Tải lên xong."); doRefresh(); });
    }

    private void doDownload() {
        final FileRow r = selectedRow();
        if (r == null || r.dir) {
            JOptionPane.showMessageDialog(this, "Hãy chọn một file để tải xuống.");
            return;
        }
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(r.name));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        final java.io.File target = fc.getSelectedFile();
        final String remote = join(cwd, r.name);
        runAsync("Đang tải xuống " + r.name + "...", () -> {
            client.download(remote, target.toPath(), progressListener());  // (đường dẫn trên server, file đích, listener)
            return null;
        }, ok -> JOptionPane.showMessageDialog(this, "Tải xuống xong."));
    }

    // ---------------------------------------------------------------- tạo thư mục / xóa

    private void doMkdir() {
        String name = JOptionPane.showInputDialog(this, "Tên thư mục mới:");
        if (name == null || name.isBlank()) return;
        final String remote = join(cwd, name.trim());
        runAsync("Đang tạo thư mục...", () -> {
            client.mkdir(remote);
            return null;
        }, ok -> doRefresh());
    }

    private void doDelete() {
        final FileRow r = selectedRow();
        if (r == null) {
            JOptionPane.showMessageDialog(this, "Hãy chọn mục cần xóa.");
            return;
        }
        int ans = JOptionPane.showConfirmDialog(this,
                "Xóa \"" + r.name + "\"? Không thể hoàn tác.", "Xác nhận xóa",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ans != JOptionPane.YES_OPTION) return;
        final String remote = join(cwd, r.name);
        runAsync("Đang xóa...", () -> {
            client.delete(remote);
            return null;
        }, ok -> doRefresh());
    }

    // ---------------------------------------------------------------- đăng xuất (D2)

    private void doLogout() {
        logout.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                try {
                    client.logout();   // báo server hủy phiên
                } finally {
                    client.close();    // luôn đóng kết nối, kể cả khi logout lỗi
                }
                return null;
            }
            @Override protected void done() {
                new LoginFrame().setVisible(true);
                MainFrame.this.dispose();
            }
        }.execute();
    }
}