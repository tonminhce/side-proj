# UTIL - Shared Utility Library

## 📋 Giới Thiệu

UTIL là thư viện tiện ích dùng chung (shared library) cho các backend services trong hệ thống VNPT GREEN. Thư viện này cung cấp các base classes, utilities, annotations, và configurations được sử dụng bởi cả GREEN và SYSTEM services.

## 🎯 Mục Đích

Thư viện này cung cấp:
- ✅ Base entities với audit trail
- ✅ Common utilities (date, string, ID generation)
- ✅ Custom annotations
- ✅ Exception handling framework
- ✅ JasperReports utilities
- ✅ Telegram integration
- ✅ Authentication helpers
- ✅ Auto-configuration cho Spring Boot

## 🛠️ Stack Công Nghệ

### Dependencies Chính

| Library | Version | Mục Đích |
|---------|---------|----------|
| Spring Boot | 4.0.0 | Framework base |
| JasperReports | 7.0.3 | Report generation |
| MinIO | 8.6.0 | Object storage |
| Apache POI | 5.4.1 | Excel processing |
| ModelMapper | 3.2.5 | Object mapping |
| FastCSV | 4.1.0 | CSV processing |
| Telegram Bot API | 9.2.0 | Telegram integration |
| Commons Lang3 | 3.18.0 | String utilities |
| Flying Saucer | 10.0.1 | HTML to PDF |

*Chia sẻ tất cả dependencies với GREEN/SYSTEM để đảm bảo tương thích*

## 📁 Cấu Trúc Thư Viện

```
util/
├── src/
│   └── main/
│       └── java/vn/vnpt/util/
│           ├── UtilsAutoConfiguration.java      # Spring Boot auto-config
│           │
│           ├── annotation/                       # Excel annotations
│           │   ├── ExcelExport.java             # Excel export annotation
│           │   ├── ExcelImport.java             # Excel import annotation
│           │   └── ...                          # Excel related annotations
│           │
│           ├── common/                           # Common utilities
│           │   ├── CommonUtil.java              # General utilities
│           │   ├── DatetimeUtil.java            # Date/Time utilities
│           │   ├── SnowflakeIdGenerator.java    # Distributed ID generator
│           │   ├── StringUtil.java              # String utilities
│           │   ├── ExcelUtils.java              # Excel utilities
│           │   ├── FileUtil.java                # File utilities
│           │   ├── JsonUtil.java                # JSON utilities
│           │   ├── User.java                    # User model
│           │   ├── CustomSecurityExpressionRoot.java  # Security expression
│           │   ├── entity/                      # Base entities
│           │   │   └── base/
│           │   │       ├── RootEntity.java      # Root entity (audit fields)
│           │   │       └── BaseEntity.java      # Base entity (extends RootEntity)
│           │   └── ...                          # Other utilities
│           │
│           ├── component/                        # Spring components
│           │   └── ...
│           │
│           ├── config/                           # Configuration
│           │   └── tenant/                      # Multi-tenant config
│           │
│           ├── exception/                        # Exception handling
│           │   ├── CustomException.java         # Custom exception
│           │   ├── GlobalExceptionHandler.java  # Global handler
│           │   ├── ResponseResult.java          # Response wrapper
│           │   ├── ReturnResult.java            # Return result
│           │   └── ...                          # Other exceptions
│           │
│           ├── jasperreports/                    # JasperReports utilities
│           │   ├── JasperUtils.java             # Jasper utilities
│           │   ├── PdfUtils.java                # PDF utilities
│           │   ├── ReportType.java              # Report type enum
│           │   └── ...                          # Report utilities
│           │
│           ├── properties/                       # Configuration properties
│           │   ├── FileProperties.java          # File properties
│           │   ├── FolderProperties.java        # Folder properties
│           │   └── TelegramProperties.java      # Telegram properties
│           │
│           └── telegram/                         # Telegram integration
│               ├── TelegramBotAPIUtil.java      # Telegram bot utility
│               └── ErrorMessageObj.java         # Error message object
│
├── pom.xml                                       # Maven config
└── README.md                                     # This file
```

## 🎨 Các Module Chính

### 1. Base Entity (RootEntity → BaseEntity)

**RootEntity** - Chứa tất cả audit fields:
- `id` (Long) - Auto-increment database sequence
- `createdBy`, `createdAt` - Audit creation
- `updatedBy`, `updatedAt` - Audit updates
- `deletedBy`, `deletedAt` - Soft delete tracking
- `isActive`, `isDeleted` - Status flags

**BaseEntity** - Kế thừa RootEntity và thêm:
- `uuid` (Long) - Snowflake distributed ID
- Auto-generate ID trong `@PrePersist`

**Sử dụng:**
```java
@Entity
@Table(name = "category")
public class Category extends BaseEntity {
    private String name;
    private String description;
    // Tự động có: uuid, id, audit fields
}
```

### 2. Common Utilities

#### DatetimeUtil
- `formatDateToString(Date, String)` - Format date to string
- `parseStringToDateWithFormat(String, String)` - Parse string to date
- `getCurrentLocalDateTime()` - Current datetime (Asia/Ho_Chi_Minh)
- `startOfDay(LocalDate)`, `endOfDay(LocalDate)` - Day boundaries
- `subDate(String, String, String)` - Calculate days difference
- Format constants: `DATE_FORMAT_1`, `DATE_FORMAT_3`, `DATE_TIME_FORMAT_3`, etc.

#### CommonUtil
- `getUserInfo()` - Lấy user info từ JWT token
- `getLoggedAccountId()` - Lấy account ID
- `getLoggedAccountName()` - Lấy username
- `getToken()` - Lấy JWT token value
- `getTenant()` - Lấy tenant từ header `x-tenant`
- `modelMapper(T, Class<U>)` - Convert object
- `modelMapperList(List<T>, Class<U>)` - Convert list
- `handleDuplicate()` - Xử lý duplicate entities/DTOs
- `getStackTraceAsString(Throwable)` - Convert exception to string

#### SnowflakeIdGenerator
Distributed ID generator với cấu trúc:
- **48 bits** - Timestamp (từ epoch 2025-01-01)
- **3 bits** - Worker ID (max 8 workers) 
- **12 bits** - Sequence (4096 ID/ms)

**Methods:**
- `generateId()` - Generate distributed Long ID
- `getWorkerIdFromPod()` - Auto-detect worker ID từ K8s POD_NAME

**Tính năng:**
- Thread-safe với AtomicLong
- Tốc độ cao: 4096 ID/millisecond
- Time-based sorting

### 3. Excel Annotations

#### @ExcelExport
Đánh dấu field để xuất Excel với các thuộc tính: `title`, `width`, `order`

**Ví dụ:**
```java
@ExcelExport(title = "Họ tên", width = 30, order = 1)
private String fullName;
```

#### @ExcelImport  
Đánh dấu field để nhập Excel với: `column` (A, B, C...), `required`

**Ví dụ:**
```java
@ExcelImport(column = "A", required = true)
private String code;
```

#### @SpecialSymbolConstraint
Validation để kiểm tra ký tự đặc biệt

**Ví dụ:**
```java
@SpecialSymbolConstraint(message = "Username không được chứa ký tự đặc biệt")
private String username;
```

### 4. Exception Handling

#### CustomException
Exception tùy chỉnh với `errorMessage`

#### GlobalExceptionHandler
Xử lý tập trung các exceptions:
- `@ExceptionHandler(CustomException.class)` - Xử lý custom exceptions
- `@ExceptionHandler(MethodArgumentNotValidException.class)` - Validation errors
- `@ExceptionHandler(Exception.class)` - Generic exceptions
- Tự động gửi error notification qua Telegram

#### ResponseResult<T>
Standard API response wrapper với:
- `success(T data)` - Success response (code 200)
- `error(String message)` - Error response (code 500)
- `error(String message, T data)` - Bad request (code 400)

### 5. JasperReports

#### JasperUtils
Utilities để export báo cáo:
- `exportPdf(JasperPrint)` - Export PDF
- `exportExcel(JasperPrint)` - Export Excel (XLSX)
- `exportHtml(JasperPrint)` - Export HTML
- `exportRtf(JasperPrint)` - Export RTF

#### ReportType
Enum định nghĩa report types: PDF, EXCEL, HTML, RTF với mimeType và extension
    
    public String getMimeType() { return mimeType; }
    public String getExtension() { return extension; }
}
```

**Ví dụ sử dụng:**
```java
JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, params, dataSource);
byte[] pdfBytes = JasperUtils.exportPdf(jasperPrint);
```

### 6. Telegram Integration

#### TelegramBotAPIUtil
Gửi error notifications qua Telegram Bot với:
- `sendErrorMessage(ErrorMessageObj)` - Gửi error với request/response body
- `sendMessageWithChatId(String, ParseMode)` - Gửi text message
- `sendDocument(File, ParseMode, String)` - Gửi file attachment

**Tính năng:**
- Tạo unique message code (Snowflake ID)
- Lưu request/response body vào temp files
- Gửi kèm stack trace nếu có exception
- Auto cleanup temp files

#### ErrorMessageObj  
Object chứa thông tin error:
- `requestBody`, `wrappedReq`, `wrappedRes` - Request/Response data
- `requestURI`, `method`, `status` - HTTP info
- `service`, `remoteHost`, `username` - Context info
- `exceptionCaught` - Exception details

**Cấu hình:**
```yaml
telegram:
  error-bot-token: ${TELEGRAM_BOT_TOKEN}
  error-group-chat-id: ${TELEGRAM_CHAT_ID}
  is-send-error: true
```

**Sử dụng:**
```java
@Service
public class OrderService {
    
    @Autowired
    private TelegramBotAPIUtil telegramBotAPIUtil;
    
    @Autowired
    private HttpServletRequest request;
    
    public void processOrder(Order order) {
        try {
            // Process order...
        } catch (Exception e) {
            ErrorMessageObj errorMsg = new ErrorMessageObj();
            errorMsg.setService("green");
            errorMsg.setRequestURI(request.getRequestURI());
            errorMsg.setMethod(request.getMethod());
            errorMsg.setStatus(500);
**Sử dụng trong GlobalExceptionHandler:**
```java
@ControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ResponseResult> handleCustomException(CustomException e) {
        ErrorMessageObj errorMsg = new ErrorMessageObj();
        errorMsg.setRequestBody(/* request body */);
        errorMsg.setExceptionCaught(e);
        errorMsg.setRemoteHost(request.getRemoteHost());
        telegramBotAPIUtil.sendErrorMessage(errorMsg);
        
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ResponseResult.error(e.getMessage()));
    }
}
```

### 7. Auto-Configuration

Module tự động cấu hình qua Spring Boot Auto-Configuration với các bean:
- `SnowflakeIdGenerator` - ID generator
- `JasperReportGenerator` - Report generator  
- `TelegramNotifier` - Telegram notifications (nếu enabled)

**Kích hoạt:** `vnpt.util.enabled=true` (default true)

## 🔧 Cách Sử Dụng

### Integration vào Project

Util library được integrate vào GREEN/SYSTEM qua Maven plugin, thêm source folder từ `../util/src/main/java`.

### Sử Dụng Utilities

```java
// Date utilities
String formatted = DatetimeUtil.formatDateToString(new Date(), "dd/MM/yyyy HH:mm:ss");
LocalDateTime now = DatetimeUtil.getCurrentLocalDateTime();

// Common utilities  
UserInfo userInfo = CommonUtil.getUserInfo();
String accountId = CommonUtil.getLoggedAccountId();
ModelMapper mapper = CommonUtil.modelMapper();

// Snowflake ID
Long id = SnowflakeIdGenerator.generateId();

// Exception handling
throw new CustomException("Error message");
```

### Sử Dụng Excel Annotations

```java
public class UserExportDTO {
    @ExcelExport(title = "Họ tên", order = 1, width = 20)
    private String fullName;
    
    @ExcelExport(title = "Email", order = 2)
    private String email;
    
    @ExcelImport(column = 0, required = true)
    @SpecialSymbolConstraint
    private String username;
}

### Sử Dụng JasperReports

```java
// Generate PDF report
Map<String, Object> params = new HashMap<>();
params.put("title", "Invoice Report");

JRBeanCollectionDataSource dataSource = new JRBeanCollectionDataSource(invoiceItems);
JasperPrint jasperPrint = JasperFillManager.fillReport(jasperReport, params, dataSource);

byte[] pdfBytes = JasperUtils.exportPdf(jasperPrint);
byte[] excelBytes = JasperUtils.exportExcel(jasperPrint);
```

## 🔧 Configuration Properties

```yaml
vnpt:
  util:
    enabled: true  # Enable auto-configuration (default)

telegram:
  error-bot-token: ${TELEGRAM_BOT_TOKEN}
  error-group-chat-id: ${TELEGRAM_CHAT_ID}
  is-send-error: true  # Enable error notifications
```

## 🚀 Bắt Đầu

Vì đây là một thư viện (library), nó không được chạy độc lập như một ứng dụng Spring Boot thông thường. Thay vào đó, nó được xây dựng và cài đặt vào local Maven repository để các service khác (`green`, `gateway`) có thể sử dụng.

### 1. Build và Cài đặt thư viện

Để build và cài đặt thư viện vào local `.m2` repository:

```bash
# Chạy từ thư mục util/
mvn clean install -DskipTests
```

Hoặc build toàn bộ project từ thư mục root:

```bash
# Chạy từ thư mục root
mvn clean install -DskipTests
```

### 2. Chạy Unit Tests

Để đảm bảo các tiện ích hoạt động đúng:

```bash
mvn test
```

### 3. Cách sử dụng trong các service khác

Các service khác sử dụng thư viện này bằng cách khai báo dependency trong `pom.xml`:

```xml
<dependency>
    <groupId>vn.vnpt</groupId>
    <artifactId>util</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

