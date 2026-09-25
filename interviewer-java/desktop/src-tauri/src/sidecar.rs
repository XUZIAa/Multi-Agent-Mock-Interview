//! 后端子进程的生命周期管理。
//!
//! 后端是 Java 进程，承担编排引擎、模型调用与数据库；语音单独跑在一个 Python 进程里，
//! 由后端自己拉起（它要在建链那一刻把密钥与音频参数下发过去，中间多一层转发没有意义）。
//! 这里只负责把「语音进程在哪」告诉后端。
//!
//! 后端启动后会在 stdout 打一行握手信息告知实际端口与本次会话的 token —— 端口由系统分配，
//! 写死会撞车。

use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tauri::async_runtime::spawn_blocking;
use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

const HANDSHAKE_PREFIX: &str = "INTERVIEWER_RPC ";
const STARTUP_TIMEOUT: Duration = Duration::from_secs(120);

/// 语音 sidecar 的位置。后端按这两个环境变量决定怎么起它，不做路径猜测。
const ENV_VOICE_EXE: &str = "INTERVIEWER_VOICE_EXE";
const ENV_VOICE_DIR: &str = "INTERVIEWER_VOICE_DIR";

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Endpoint {
    pub host: String,
    pub port: u16,
    pub token: String,
}

impl Endpoint {
    pub fn http_base(&self) -> String {
        format!("http://{}:{}", self.host, self.port)
    }
    pub fn ws_events(&self) -> String {
        format!("ws://{}:{}/events?token={}", self.host, self.port, self.token)
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct BackendInfo {
    pub http_base: String,
    pub ws_events: String,
    pub token: String,
}

impl From<&Endpoint> for BackendInfo {
    fn from(e: &Endpoint) -> Self {
        Self {
            http_base: e.http_base(),
            ws_events: e.ws_events(),
            token: e.token.clone(),
        }
    }
}

/// 进程句柄与连接信息。前端随时可能刷新，信息要能重复取用。
#[derive(Default)]
pub struct Backend {
    endpoint: Mutex<Option<Endpoint>>,
    child: Mutex<Option<CommandChild>>,
}

impl Backend {
    pub fn info(&self) -> Option<BackendInfo> {
        self.endpoint.lock().ok()?.as_ref().map(BackendInfo::from)
    }

    /// 结束子进程。窗口关闭时必须调用。
    ///
    /// 后端进程死掉，它拉起的语音进程会读到 stdin 的 EOF 而自行收摊，麦克风不会被占着不放。
    pub fn shutdown(&self) {
        if let Ok(mut guard) = self.child.lock() {
            if let Some(child) = guard.take() {
                let _ = child.kill();
            }
        }
        if let Ok(mut guard) = self.endpoint.lock() {
            *guard = None;
        }
    }
}

/// 找到仓库根：backend/ 与 voice-sidecar/ 并列的那一层。
///
/// 不用固定的 `..` 层数：cargo 与 tauri dev 的工作目录不一定是同一层，数错了只会得到
/// 「jar 不存在」这种指错方向的报错。按标志文件往上找，改目录结构也不会崩。
fn dev_root() -> Result<PathBuf, String> {
    let cwd = std::env::current_dir()
        .map_err(|e| format!("读取工作目录失败: {e}"))?
        .canonicalize()
        .map_err(|e| format!("解析工作目录失败: {e}"))?;
    cwd.ancestors()
        .find(|dir| {
            dir.join("backend/pom.xml").exists() && dir.join("voice-sidecar/pyproject.toml").exists()
        })
        .map(|dir| dir.to_path_buf())
        .ok_or_else(|| format!("从 {} 往上找不到项目根目录", cwd.display()))
}

/// 拉起后端并等它报出连接信息。
///
/// 开发态直接跑 `mvn package` 产出的 jar，打包态走随包分发的 JRE；两者的差异只在这里。
pub async fn launch(app: AppHandle) -> Result<BackendInfo, String> {
    let state = app.state::<Arc<Backend>>();
    if let Some(info) = state.info() {
        return Ok(info);
    }

    let shell = app.shell();
    let command = if cfg!(debug_assertions) {
        let root = dev_root()?;
        let jar = root.join("backend/target/interviewer-backend.jar");
        if !jar.exists() {
            return Err(format!(
                "后端 jar 不存在：{}。先在 backend/ 下跑一次 mvn -DskipTests package",
                jar.display()
            ));
        }
        shell
            .command("java")
            .args(java_args(&jar))
            .current_dir(&root)
            .env(ENV_VOICE_DIR, root.join("voice-sidecar"))
    } else {
        let resources = app
            .path()
            .resource_dir()
            .map_err(|e| format!("定位资源目录失败: {e}"))?;
        let dir = resources.join("backend");
        let java = dir.join("jre/bin/java.exe");
        let jar = dir.join("interviewer-backend.jar");
        if !java.exists() {
            return Err(format!("随包 JRE 缺失: {}", java.display()));
        }
        if !jar.exists() {
            return Err(format!("随包后端缺失: {}", jar.display()));
        }
        // 语音进程带着 numpy、sounddevice 一堆动态库，只能以目录形态分发：
        // 单文件模式每次启动都要解压，会白等好几秒。
        let voice = resources.join("voice/interviewer-voice.exe");
        if !voice.exists() {
            return Err(format!("随包语音进程缺失: {}", voice.display()));
        }
        shell
            .command(java.to_string_lossy().to_string())
            .args(java_args(&jar))
            .current_dir(&dir)
            .env(ENV_VOICE_EXE, voice)
    };

    let (mut rx, child) = command
        .spawn()
        .map_err(|e| format!("启动后端进程失败: {e}"))?;

    if let Ok(mut guard) = state.child.lock() {
        *guard = Some(child);
    }

    let deadline = Instant::now() + STARTUP_TIMEOUT;
    let mut stderr_tail: Vec<String> = Vec::new();

    while Instant::now() < deadline {
        let event = match tokio::time::timeout(Duration::from_secs(5), rx.recv()).await {
            Ok(Some(ev)) => ev,
            Ok(None) => break,
            Err(_) => continue,
        };
        match event {
            CommandEvent::Stdout(bytes) => {
                let line = String::from_utf8_lossy(&bytes).to_string();
                for part in line.lines() {
                    if let Some(payload) = part.trim().strip_prefix(HANDSHAKE_PREFIX) {
                        let endpoint: Endpoint = serde_json::from_str(payload)
                            .map_err(|e| format!("握手信息无法解析: {e} / 原文: {payload}"))?;
                        let info = BackendInfo::from(&endpoint);
                        if let Ok(mut guard) = state.endpoint.lock() {
                            *guard = Some(endpoint);
                        }
                        // 握手之后仍要持续排空管道，否则子进程写满 stdout 会被阻塞
                        drain(app.clone(), rx);
                        return Ok(info);
                    }
                }
            }
            CommandEvent::Stderr(bytes) => {
                let text = String::from_utf8_lossy(&bytes).to_string();
                for part in text.lines() {
                    stderr_tail.push(part.to_string());
                }
                if stderr_tail.len() > 40 {
                    let cut = stderr_tail.len() - 40;
                    stderr_tail.drain(0..cut);
                }
            }
            CommandEvent::Terminated(status) => {
                return Err(format!(
                    "后端进程提前退出（code={:?}）。最后输出：\n{}",
                    status.code,
                    stderr_tail.join("\n")
                ));
            }
            _ => {}
        }
    }

    state.shutdown();
    Err(format!(
        "后端在 {} 秒内没有报出连接信息。最后输出：\n{}",
        STARTUP_TIMEOUT.as_secs(),
        stderr_tail.join("\n")
    ))
}

/// JVM 参数。
///
/// 编码必须逐项写死：Windows 上 JVM 默认按系统代码页（GBK）解释文件与标准流，提示词、
/// 人格锚点、日志全是中文，一处按 GBK 落盘后面全乱。
fn java_args(jar: &PathBuf) -> Vec<String> {
    vec![
        "-Dfile.encoding=UTF-8".to_string(),
        "-Dstdout.encoding=UTF-8".to_string(),
        "-Dstderr.encoding=UTF-8".to_string(),
        // 桌面单用户场景，堆给小一点，省下的内存留给语音进程的音频缓冲
        "-Xmx512m".to_string(),
        "-jar".to_string(),
        jar.to_string_lossy().to_string(),
        "--port".to_string(),
        "0".to_string(),
    ]
}

/// 持续读取子进程输出：转发日志、感知退出。
fn drain(app: AppHandle, mut rx: tauri::async_runtime::Receiver<CommandEvent>) {
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stderr(bytes) => {
                    let text = String::from_utf8_lossy(&bytes).to_string();
                    for line in text.lines() {
                        if !line.trim().is_empty() {
                            let _ = app.emit("backend-log", line.to_string());
                        }
                    }
                }
                CommandEvent::Terminated(status) => {
                    let _ = app.emit("backend-exit", status.code);
                    if let Some(state) = app.try_state::<Arc<Backend>>() {
                        state.shutdown();
                    }
                    return;
                }
                _ => {}
            }
        }
    });
}

#[tauri::command]
pub async fn backend_start(app: AppHandle) -> Result<BackendInfo, String> {
    launch(app).await
}

#[tauri::command]
pub fn backend_info(app: AppHandle) -> Option<BackendInfo> {
    app.state::<Arc<Backend>>().info()
}

#[tauri::command]
pub async fn backend_stop(app: AppHandle) -> Result<(), String> {
    let state = app.state::<Arc<Backend>>().inner().clone();
    spawn_blocking(move || state.shutdown())
        .await
        .map_err(|e| format!("停止后端失败: {e}"))
}
