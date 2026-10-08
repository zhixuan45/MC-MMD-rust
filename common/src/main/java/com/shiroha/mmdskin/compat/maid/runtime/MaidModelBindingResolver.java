package com.shiroha.mmdskin.compat.maid.runtime;

import com.shiroha.mmdskin.config.UIConstants;

/** 文件职责：按本地明确选择、当前会话远端绑定、原版的顺序解析女仆外观。 */
final class MaidModelBindingResolver {
    private MaidModelBindingResolver() {
    }

    static String resolve(String localPreference, String remoteBinding) {
        if (localPreference != null && !localPreference.isBlank()) return localPreference;
        if (remoteBinding != null && !remoteBinding.isBlank()) return remoteBinding;
        return UIConstants.DEFAULT_MODEL_NAME;
    }
}
