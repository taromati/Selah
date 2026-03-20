package me.taromati.almah.web.setup.dto;

public record RestartResult(boolean restarting, String message) {
    public static RestartResult ok() {
        return new RestartResult(true, null);
    }

    public static RestartResult noService() {
        return new RestartResult(false, "서비스가 등록되어 있지 않습니다. 수동으로 재시작해주세요.");
    }
}
