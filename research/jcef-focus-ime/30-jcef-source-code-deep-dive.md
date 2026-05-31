# JCEF 源码深度分析 - 焦点管理机制

**调研时间**: 2026-05-30
**调研目标**: 基于 JetBrains jcef 仓库源码深度分析焦点管理机制

---

## 1. 核心发现

### 1.1 JCEF 焦点管理架构

**双向同步机制**:

1. **Swing → Chromium**: `FocusListener` 监听 Swing 组件焦点变化，调用 `browser.setFocus(boolean)` 通知 Chromium
2. **Chromium → Swing**: `CefFocusHandler` 回调（`onTakeFocus`/`onSetFocus`/`onGotFocus`）通过 `PropertyChangeListener` 和 `FocusTraversalPolicy` 管理 Swing 焦点

### 1.2 关键文件

```
github.com/JetBrains/jcef/master/java/org/cef/
├── handler/
│   ├── CefFocusHandler.java          # 焦点处理器接口
│   └── CefFocusHandlerAdapter.java   # 焦点处理器适配器
├── browser/
│   ├── CefBrowser.java                # 浏览器接口
│   ├── CefBrowserOsr.java             # OSR 渲染浏览器实现
│   ├── CefBrowserOsrWithHandler.java  # 带自定义渲染处理的 OSR 浏览器
│   └── CefBrowserWr.java              # 窗口模式浏览器实现
└── CefClient.java                    # 客户端（焦点处理的核心实现）
```

---

## 2. CefFocusHandler 回调链

### 2.1 FocusSource 枚举

```java
// /org/cef/handler/CefFocusHandler.java
public interface CefFocusHandler {
    enum FocusSource {
        FOCUS_SOURCE_NAVIGATION, //!< 来自 API 的显式导航
        FOCUS_SOURCE_SYSTEM      //!< 来自系统生成的焦点事件
    }

    public void onTakeFocus(CefBrowser browser, boolean next);
    public boolean onSetFocus(CefBrowser browser, FocusSource source);
    public void onGotFocus(CefBrowser browser);
}
```

### 2.2 CefClient 焦点状态追踪

```java
// /org/cef/CefClient.java
private volatile CefBrowser focusedBrowser_ = null;

// PropertyChangeListener 监听 Swing 焦点变化
private final PropertyChangeListener propertyChangeListener = new PropertyChangeListener() {
    @Override
    public void propertyChange(PropertyChangeEvent evt) {
        if (focusedBrowser_ != null) {
            Component browserUI = focusedBrowser_.getUIComponent();
            if (browserUI == null) return;
            Object oldUI = evt.getOldValue();
            if (isPartOf(oldUI, browserUI)) {
                focusedBrowser_.setFocus(false);  // 通知浏览器失去焦点
                focusedBrowser_ = null;
            }
        }
    }
};
```

### 2.3 onTakeFocus 实现（浏览器失去焦点）

```java
// /org/cef/CefClient.java 第 318-341 行
@Override
public void onTakeFocus(CefBrowser browser, boolean next) {
    if (browser == null) return;

    browser.setFocus(false);  // 通知底层浏览器失去焦点
    Component uiComponent = browser.getUIComponent();
    if (uiComponent == null) return;
    Container parent = uiComponent.getParent();
    if (parent != null) {
        FocusTraversalPolicy policy = null;
        while (parent != null) {
            policy = parent.getFocusTraversalPolicy();
            if (policy != null) break;
            parent = parent.getParent();
        }
        if (policy != null) {
            Component nextComp = next
                    ? policy.getComponentAfter(parent, uiComponent)
                    : policy.getComponentBefore(parent, uiComponent);
            if (nextComp == null) {
                policy.getDefaultComponent(parent).requestFocus();
            } else {
                nextComp.requestFocus();
            }
        }
    }
    focusedBrowser_ = null;
    if (focusHandler_ != null) focusHandler_.onTakeFocus(browser, next);
}
```

---

## 3. OSR 模式焦点处理

### 3.1 GLCanvas 焦点监听器

```java
// /org/cef/browser/CefBrowserOsr.java 第 182-195 行
canvas_.setFocusable(true);  // 必须设置为可聚焦
canvas_.addFocusListener(new FocusListener() {
    @Override
    public void focusLost(FocusEvent e) {
        setFocus(false);  // 调用 native N_SetFocus(false)
    }

    @Override
    public void focusGained(FocusEvent e) {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        setFocus(true);   // 调用 native N_SetFocus(true)
    }
});
```

### 3.2 OSR 浏览器创建后焦点处理

```java
// /org/cef/browser/CefBrowserOsr.java 第 319-323 行
} else if (hasParent && justCreated_) {
    notifyAfterParentChanged();
    setFocus(true);  // OSR 模式创建后立即请求焦点
    justCreated_ = false;
}
```

---

## 4. 已知 Bug 和 Workaround

### 4.1 CEF Issue #1437 - 浏览器矩形初始化

```java
// /org/cef/browser/CefBrowserOsr.java 第 44 行
private Rectangle browser_rect_ = new Rectangle(0, 0, 1, 1); // Work around CEF issue #1437.
```

### 4.2 macOS Java 8 Retina 缩放因子读取错误

```java
// /org/cef/browser/CefBrowserOsr.java 第 92-114 行
if (OS.isMacintosh()
        && System.getProperty("java.runtime.version").startsWith("1.8")) {
    // 使用反射获取真实的缩放因子
    try {
        if (scaleFactorAccessor == null) {
            scaleFactorAccessor = getClass()
                    .getClassLoader()
                    .loadClass("sun.awt.CGraphicsDevice")
                    .getDeclaredMethod("getScaleFactor");
        }
        Object factor = scaleFactorAccessor.invoke(config.getDevice());
        if (factor instanceof Integer) {
            scaleFactor_ = ((Integer) factor).doubleValue();
        }
    } catch (...) {
        scaleFactor_ = 1.0;
    }
}
```

### 4.3 macOS 上创建后不请求焦点

```java
// /org/cef/browser/CefBrowserWr.java 第 294 行
} else if (hasParent && justCreated_) {
    setParent(windowHandle, canvas);
    // setFocus(true); do not request focus on show  ← 故意注释掉！
    justCreated_ = false;
}
```

---

## 5. 焦点同步流程图

```
┌─────────────────────────────────────────────────────────────────┐
│                      Swing 焦点系统                               │
│  Component.requestFocus() / FocusListener                       │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                    CefClient 焦点管理                            │
│  • focusedBrowser_ 状态追踪                                       │
│  • PropertyChangeListener 监听 Swing 焦点变化                     │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                 CefFocusHandler 回调                            │
│  onTakeFocus → onSetFocus → onGotFocus                         │
└─────────────────────┬───────────────────────────────────────────┘
                      │
                      ▼
┌─────────────────────────────────────────────────────────────────┐
│              CefBrowser_N.native N_SetFocus()                  │
│                    (JNI 调用 Chromium)                          │
└─────────────────────────────────────────────────────────────────┘
```

---

## 6. 关键代码路径

### 6.1 setFocus 方法调用链

```java
// CefBrowser_N.java 第 354-360 行
@Override
public void setFocus(boolean enable) {
    try {
        N_SetFocus(enable);  // Native 方法调用
    } catch (UnsatisfiedLinkError ule) {
        ule.printStackTrace();
    }
}
```

### 6.2 CefBrowserOsr 中鼠标按下触发焦点

```java
// CefBrowserOsr.java 第 203-214 行
canvas_.addMouseListener(new MouseListener() {
    @Override
    public void mousePressed(MouseEvent e) {
        sendMouseEvent(e);  // 鼠标按下同时触发焦点获取
    }
});
```

---

## 7. 与项目实现的对比

| 功能                | JCEF 源码                    | 项目实现 | 差异                                 |
| ------------------- | ---------------------------- | -------- | ------------------------------------ |
| FocusListener 注册  | `CefBrowserOsr.java:182-195` | 缺失     | ❌ 项目未注册 GLCanvas FocusListener |
| CefFocusHandler     | `CefClient.java:318-371`     | 缺失     | ❌ 项目未实现 CefFocusHandler        |
| focusedBrowser 追踪 | `CefClient.java:33`          | 缺失     | ❌ 项目无焦点状态追踪                |
| setFocus 调用       | `CefBrowser_N.java:354`      | 缺失     | ❌ 项目未调用 setFocus               |

---

## 8. 参考资源

| 资源                 | URL                                                                                     |
| -------------------- | --------------------------------------------------------------------------------------- |
| JCEF GitHub 仓库     | https://github.com/JetBrains/jcef                                                       |
| CefFocusHandler 源码 | https://github.com/JetBrains/jcef/blob/master/java/org/cef/handler/CefFocusHandler.java |
| CefBrowserOsr 源码   | https://github.com/JetBrains/jcef/blob/master/java/org/cef/browser/CefBrowserOsr.java   |
| CefClient 源码       | https://github.com/JetBrains/jcef/blob/master/java/org/cef/CefClient.java               |

---

## 9. 下一步行动

1. **实现 GLCanvas FocusListener** - 在 BrowserPanel 中注册焦点监听器
2. **实现 CefFocusHandler** - 处理 Chromium 焦点回调
3. **添加 focusedBrowser 追踪** - 防止递归焦点调用
4. **测试 setFocus 调用** - 验证双向焦点同步
