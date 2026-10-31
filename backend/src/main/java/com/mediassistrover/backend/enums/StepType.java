package com.mediassistrover.backend.enums;

/** Fixed protocol handlers. Business actions are configured in Step.paramsJson. */
public enum StepType {
    MOVE("Di chuyển", StepExecutorKind.PI),
    INTERACT("Tương tác", StepExecutorKind.MIXED),
    DATA_SERVICE("Dịch vụ dữ liệu", StepExecutorKind.SERVER),
    AUTHENTICATE("Xác thực", StepExecutorKind.SERVER),
    CONTROL_HARDWARE("Điều khiển phần cứng", StepExecutorKind.PI),
    HANDLE_PAYLOAD("Xử lý vật phẩm", StepExecutorKind.SERVER),
    EXECUTE_SENSING("Thu thập cảm biến", StepExecutorKind.MIXED),
    NOTIFY("Thông báo", StepExecutorKind.SERVER),
    WAIT("Chờ", StepExecutorKind.MIXED),
    CHECK_CONDITION("Kiểm tra điều kiện", StepExecutorKind.SERVER),
    SET_CONTEXT("Gán context", StepExecutorKind.SERVER);

    private final String displayName;
    private final StepExecutorKind executorKind;
    StepType(String displayName, StepExecutorKind executorKind) {
        this.displayName = displayName;
        this.executorKind = executorKind;
    }
    public String getStepTypeCode() { return name(); }
    public String getDisplayName() { return displayName; }
    public StepExecutorKind getExecutorKind() { return executorKind; }
    public StepExecutorKind getExecutorKind(int schemaVersion) {
        if(schemaVersion==2 && this==INTERACT) return StepExecutorKind.SERVER;
        if(schemaVersion==2 && (this==CHECK_CONDITION || this==SET_CONTEXT)) return StepExecutorKind.PI;
        return executorKind;
    }
}
