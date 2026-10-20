package vn.edu.fs.gui;

import vn.edu.fs.common.FsException;

/** Dịch lỗi (từ server hoặc từ mạng) sang thông báo tiếng Việt dễ hiểu cho người dùng. */
final class Errors {
    private Errors() { }

    static String message(Throwable t) {
        Throwable c = t;
        // Bóc các lớp bọc (ExecutionException...) cho tới khi gặp FsException hoặc hết nguyên nhân
        while (c.getCause() != null && !(c instanceof FsException)) {
            c = c.getCause();
        }

        if (c instanceof FsException) {
            switch (((FsException) c).code().name()) {
                case "UNAUTHORIZED": return "Sai ID hoặc mật khẩu, hoặc tài khoản đang bị khóa tạm. Thử lại sau.";
                case "FORBIDDEN":    return "Bạn không có quyền thực hiện thao tác này.";
                case "NOT_FOUND":    return "Không tìm thấy file hoặc thư mục.";
                case "LOCKED":       return "File đang được người khác sử dụng. Thử lại sau ít giây.";
                case "TOO_LARGE":    return "File quá lớn so với giới hạn của máy chủ.";
                case "BAD_REQUEST":  return "Yêu cầu không hợp lệ hoặc dữ liệu bị lỗi khi truyền.";
                default:             return "Máy chủ báo lỗi. Vui lòng thử lại.";
            }
        }
        if (c instanceof java.net.ConnectException || c instanceof java.net.UnknownHostException) {
            return "Không kết nối được máy chủ. Kiểm tra host và cổng.";
        }
        if (c instanceof java.net.SocketTimeoutException) {
            return "Máy chủ phản hồi quá chậm. Vui lòng thử lại.";
        }
        if (c instanceof javax.net.ssl.SSLException) {
            return "Kết nối bảo mật (TLS) thất bại. Kiểm tra chứng chỉ của máy chủ.";
        }
        if (c instanceof java.nio.file.NoSuchFileException) {
            return "Không tìm thấy file truststore. Kiểm tra biến FS_TRUSTSTORE hoặc thư mục certs/.";
        }
        if (c instanceof IllegalStateException) {
            return "Thiếu cấu hình: hãy đặt biến môi trường FS_TRUSTSTORE_PASSWORD.";
        }
        return "Có lỗi xảy ra. Vui lòng thử lại.";   // không hiện stack trace cho người dùng
    }
}