import {
  AudioLines,
  Check,
  ChevronRight,
  ExternalLink,
  Eye,
  EyeOff,
  Info,
  Loader2,
  Save,
  ShieldCheck,
  Sparkles,
  X,
} from "lucide-react";
import { openUrl } from "@tauri-apps/plugin-opener";
import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";

import { PageContainer } from "@/components/page-container";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Slider } from "@/components/ui/slider";
import { Switch } from "@/components/ui/switch";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { type AppSettings, type Schemas, api } from "@/lib/backend";
import { cn } from "@/lib/utils";

type Catalog = Schemas["Catalog"];
type ProviderOption = Schemas["ProviderOption"];
type AudioDevices = Schemas["AudioDevices"];
type ProbeOutcome = Schemas["ProbeOutcome"];
type ModelsOutcome = Schemas["ModelsOutcome"];

type ProbeState = Record<string, { pending: boolean; result?: ProbeOutcome }>;
type FetchState = { pending: boolean; result?: ModelsOutcome };

/** WebView 里 <a target="_blank"> 不会唤起系统浏览器，必须交给 opener */
async function openConsole(url: string): Promise<void> {
  try {
    await openUrl(url);
  } catch {
    await navigator.clipboard.writeText(url).catch(() => undefined);
    toast.error("打不开浏览器，链接已复制到剪贴板");
  }
}

/** 接入地址与模型清单由用户自己填的那两个供应商，与后端的 chatCatalog 覆盖规则一致。 */
const CUSTOM_PROVIDERS = new Set(["custom", "openai_compat"]);

const TAB_ITEMS = [
  { value: "keys", label: "模型选择" },
  { value: "roles", label: "角色绑定" },
  { value: "realtime", label: "实时语音" },
  { value: "audio", label: "音频" },
  { value: "features", label: "功能" },
];

export function SettingsView({ onOpenAbout }: { onOpenAbout: () => void }) {
  const [catalog, setCatalog] = useState<Catalog | null>(null);
  const [devices, setDevices] = useState<AudioDevices | null>(null);
  const [settings, setSettings] = useState<AppSettings | null>(null);
  const [keyPresent, setKeyPresent] = useState<Record<string, boolean>>({});
  const [keyDraft, setKeyDraft] = useState<Record<string, string>>({});
  const [reveal, setReveal] = useState<Record<string, boolean>>({});
  const [probe, setProbe] = useState<ProbeState>({});
  const [fetchState, setFetchState] = useState<FetchState>({ pending: false, result: undefined });
  const [modelDraft, setModelDraft] = useState("");
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    try {
      const [cat, dev, cfg] = await Promise.all([
        api.get<Catalog>("/catalog"),
        api.get<AudioDevices>("/audio/devices"),
        api.get<AppSettings>("/config"),
      ]);
      setCatalog(cat);
      setDevices(dev);
      setSettings(cfg);
      const keys = Array.from(
        new Set([...cat.chat.map((p) => p.credential_key), ...cat.realtime.map((p) => p.credential_key)]),
      );
      const present = await Promise.all(
        keys.map((k) =>
          api
            .get<{ present: boolean }>(`/config/keys/${k}`)
            .then((r) => [k, r.present] as const)
            .catch(() => [k, false] as const),
        ),
      );
      setKeyPresent(Object.fromEntries(present));
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "加载设置失败");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const save = async () => {
    if (!settings) return;
    // openai_compat 没有默认接入地址，绑了它却不填地址，保存时看不出问题，
    // 要到启动面试才在后端炸出来——离操作现场太远，这里提前拦下
    const dangling = catalog?.roles.find((r) => {
      const binding = settings.roles[r.key];
      return binding?.provider === "openai_compat" && !settings.custom_chat.base_url.trim();
    });
    if (dangling) {
      toast.error(`「${dangling.label}」绑定了 OpenAI 兼容中转，但自定义端点的接入地址是空的`);
      return;
    }
    setSaving(true);
    try {
      // 先落密钥再存配置：配置保存会触发模型客户端重建，届时应当已能取到新密钥
      for (const [providerKey, value] of Object.entries(keyDraft)) {
        if (!value.trim()) continue;
        await api.post("/config/keys", { provider_key: providerKey, api_key: value.trim() });
      }
      await api.post("/config", settings);
      setKeyDraft({});
      const refreshed = Object.fromEntries(
        Object.entries(keyPresent).map(([k, v]) => [k, v || Boolean(keyDraft[k]?.trim())]),
      );
      setKeyPresent(refreshed);
      toast.success("设置已保存");
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  const pullModels = async () => {
    // 与后端同一优先级：把界面草稿（地址 + Key）带过去，拉的就是眼前这份配置
    setFetchState({ pending: true, result: undefined });
    try {
      const result = await api.post<ModelsOutcome>("/config/models", {
        provider_key: "openai_compat",
        base_url: settings?.custom_chat.base_url ?? "",
        api_key: keyDraft["openai_compat"]?.trim() ?? "",
      });
      setFetchState({ pending: false, result });
      if (result.ok && settings) {
        setSettings({ ...settings, custom_chat: { ...settings.custom_chat, models: result.models } });
      }
    } catch (err) {
      setFetchState({
        pending: false,
        result: {
          ok: false,
          detail: err instanceof Error ? err.message : "拉取失败",
          models: [],
        },
      });
    }
  };

  // 手动输入支持一次粘多个：换行或逗号分隔，去重后并入清单
  const commitModelDraft = () => {
    const names = modelDraft
      .split(/[\n,，]/)
      .map((s) => s.trim())
      .filter(Boolean);
    setModelDraft("");
    if (names.length === 0 || !settings) return;
    const merged = [...(settings.custom_chat.models ?? [])];
    for (const name of names) {
      if (!merged.includes(name)) merged.push(name);
    }
    setSettings({ ...settings, custom_chat: { ...settings.custom_chat, models: merged } });
  };

  const removeModel = (name: string) => {
    if (!settings) return;
    setSettings({
      ...settings,
      custom_chat: {
        ...settings.custom_chat,
        models: (settings.custom_chat.models ?? []).filter((m) => m !== name),
      },
    });
  };

  const runProbe = async (id: string, body: Record<string, unknown>) => {
    setProbe((prev) => ({ ...prev, [id]: { pending: true } }));
    try {
      const result = await api.post<ProbeOutcome>("/config/probe", body);
      setProbe((prev) => ({ ...prev, [id]: { pending: false, result } }));
    } catch (err) {
      setProbe((prev) => ({
        ...prev,
        [id]: {
          pending: false,
          result: {
            ok: false,
            detail: err instanceof Error ? err.message : "测试失败",
            latency_ms: 0,
          },
        },
      }));
    }
  };

  if (!catalog || !settings || !devices) {
    return (
      <PageContainer title="设置" description="模型、密钥、语言与音频">
        <div className="space-y-4">
          <Skeleton className="h-40 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </PageContainer>
    );
  }

  const realtime = catalog.realtime.find((p) => p.key === settings.realtime.provider);
  // 自定义端点的 Key 不在这张卡：custom（本机 Ollama）通常无 Key，
  // openai_compat 的 Key 挪进「自定义端点」卡，与接入地址同屏
  const credentialKeys = Array.from(
    new Set([
      ...catalog.chat
        .filter((p) => !CUSTOM_PROVIDERS.has(p.key))
        .map((p) => p.key),
      ...catalog.realtime.map((p) => p.credential_key),
    ]),
  );
  const providerByKey = new Map<string, ProviderOption>(
    [...catalog.chat, ...catalog.realtime].map((p) => [p.credential_key, p]),
  );

  return (
    <PageContainer
      title="设置"
      description="所有数据留在本机，密钥存入系统凭据管理器"
      actions={
        <Button onClick={() => void save()} disabled={saving}>
          {saving ? <Loader2 className="animate-spin" /> : <Save />}
          保存
        </Button>
      }
    >
      <Tabs defaultValue="keys">
        <TabsList
          variant="line"
          className="h-auto w-full justify-start gap-6 rounded-none border-b p-0"
        >
          {TAB_ITEMS.map((t) => (
            <TabsTrigger
              key={t.value}
              value={t.value}
              className="flex-none px-0 pb-2.5 text-[13.5px] data-[state=active]:font-semibold"
            >
              {t.label}
            </TabsTrigger>
          ))}
        </TabsList>

        <TabsContent value="keys" className="mt-6">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <ShieldCheck className="text-success size-4" />
              API Key
            </CardTitle>
            <CardDescription>
              填过的密钥不会回传到界面。改动后点右上角保存，测试按钮可随时验证是否可用
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {credentialKeys.map((key) => {
              const provider = providerByKey.get(key);
              const state = probe[`key:${key}`];
              return (
                <div key={key} className="space-y-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <Label className="text-sm">{provider?.label ?? key}</Label>
                    <div className="flex items-center gap-2">
                      {keyPresent[key] && !keyDraft[key] && (
                        <span className="text-success flex items-center gap-1 text-xs">
                          <Check className="size-3" />
                          已配置
                        </span>
                      )}
                      {provider?.console_url && (
                        <button
                          type="button"
                          onClick={() => void openConsole(provider.console_url)}
                          className="text-muted-foreground hover:text-foreground flex items-center gap-1 text-xs transition-colors"
                        >
                          获取
                          <ExternalLink className="size-3" />
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <div className="relative flex-1">
                      <Input
                        type={reveal[key] ? "text" : "password"}
                        placeholder={keyPresent[key] ? "已保存，留空表示不修改" : "粘贴 API Key"}
                        value={keyDraft[key] ?? ""}
                        onChange={(e) =>
                          setKeyDraft((prev) => ({ ...prev, [key]: e.target.value }))
                        }
                        className="pr-9"
                      />
                      <button
                        type="button"
                        onClick={() => setReveal((prev) => ({ ...prev, [key]: !prev[key] }))}
                        className="text-muted-foreground hover:text-foreground absolute top-1/2 right-2 -translate-y-1/2"
                        aria-label={reveal[key] ? "隐藏" : "显示"}
                      >
                        {reveal[key] ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                      </button>
                    </div>
                    <Button
                      variant="outline"
                      disabled={state?.pending || (!keyPresent[key] && !keyDraft[key]?.trim())}
                      onClick={() =>
                        void runProbe(`key:${key}`, {
                          provider_key: key,
                          api_key: keyDraft[key]?.trim() ?? "",
                        })
                      }
                    >
                      {state?.pending ? <Loader2 className="animate-spin" /> : null}
                      测试
                    </Button>
                  </div>
                  {state?.result && <ProbeLine result={state.result} />}
                </div>
              );
            })}
          </CardContent>
        </Card>

        <Card className="mt-4">
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Sparkles className="text-muted-foreground size-4" />
              自定义端点
            </CardTitle>
            <CardDescription>
              供应商选「自定义 OpenAI 兼容端点」（本机 Ollama 等）或「自定义 OpenAI
              兼容中转」时，从这里取接入地址与模型候选。地址留空时本机端点回落到
              http://127.0.0.1:11434/v1（Ollama 默认）
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <Field label="接入地址（base_url）">
              <Input
                placeholder="https://your-relay.com/v1"
                value={settings.custom_chat.base_url ?? ""}
                onChange={(e) =>
                  setSettings({
                    ...settings,
                    custom_chat: { ...settings.custom_chat, base_url: e.target.value },
                  })
                }
              />
            </Field>
            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <Label className="text-muted-foreground text-xs font-normal">
                  中转 API Key（本机 Ollama 留空）
                </Label>
                {keyPresent["openai_compat"] && !keyDraft["openai_compat"] && (
                  <span className="text-success flex items-center gap-1 text-xs">
                    <Check className="size-3" />
                    已配置
                  </span>
                )}
              </div>
              <div className="relative">
                <Input
                  type={reveal["openai_compat"] ? "text" : "password"}
                  placeholder={
                    keyPresent["openai_compat"] ? "已保存，留空表示不修改" : "中转站的 API Key"
                  }
                  value={keyDraft["openai_compat"] ?? ""}
                  onChange={(e) =>
                    setKeyDraft((prev) => ({ ...prev, openai_compat: e.target.value }))
                  }
                  className="pr-9"
                />
                <button
                  type="button"
                  onClick={() =>
                    setReveal((prev) => ({ ...prev, openai_compat: !prev["openai_compat"] }))
                  }
                  className="text-muted-foreground hover:text-foreground absolute top-1/2 right-2 -translate-y-1/2"
                  aria-label={reveal["openai_compat"] ? "隐藏" : "显示"}
                >
                  {reveal["openai_compat"] ? (
                    <EyeOff className="size-4" />
                  ) : (
                    <Eye className="size-4" />
                  )}
                </button>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={fetchState.pending}
                onClick={() => void pullModels()}
              >
                {fetchState.pending ? <Loader2 className="animate-spin" /> : null}
                拉取模型列表
              </Button>
              {fetchState.result && (
                <span
                  className={cn(
                    "text-xs",
                    fetchState.result.ok ? "text-success" : "text-destructive",
                  )}
                >
                  {fetchState.result.detail}
                </span>
              )}
            </div>
            <Field label={`模型清单（${settings.custom_chat.models?.length ?? 0} 个）`}>
              <div className="bg-muted/25 flex min-h-16 flex-wrap content-start items-center gap-1.5 rounded-md border p-2">
                {(settings.custom_chat.models ?? []).map((name) => (
                  <Badge
                    key={name}
                    variant="secondary"
                    className="gap-1 pr-1 font-mono text-[11.5px] font-normal"
                  >
                    {name}
                    <button
                      type="button"
                      onClick={() => removeModel(name)}
                      aria-label={`删除 ${name}`}
                      className="hover:bg-accent hover:text-destructive flex size-4 items-center justify-center rounded-full transition-colors"
                    >
                      <X className="size-2.5" />
                    </button>
                  </Badge>
                ))}
                <input
                  value={modelDraft}
                  onChange={(e) => setModelDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key !== "Enter") return;
                    e.preventDefault();
                    commitModelDraft();
                  }}
                  onBlur={() => commitModelDraft()}
                  placeholder={
                    (settings.custom_chat.models?.length ?? 0) === 0
                      ? "点上方「拉取模型列表」，或输入模型名后回车"
                      : "输入模型名，回车添加"
                  }
                  className="placeholder:text-muted-foreground/60 h-7 min-w-40 flex-1 bg-transparent px-1 font-mono text-xs outline-none"
                />
              </div>
            </Field>
            <p className="text-muted-foreground text-xs">
              拉取调用 /models 只列名字不消耗额度，成功即代表地址与 Key 可用。这里的清单只是候选，绑定角色时也可以直接输入清单外的模型名
            </p>
          </CardContent>
        </Card>

        </TabsContent>

        <TabsContent value="roles" className="mt-6">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <Sparkles className="text-primary size-4" />
              角色绑定
            </CardTitle>
            <CardDescription>
              四个角色可分别绑定。导演与复盘吃长上下文，提词与守卫在延迟敏感链路上要快；
              模型名可以直接输入，不一定要在候选清单里。接入地址、Key
              与模型清单在「模型选择」标签配置
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {catalog.roles.map((role) => {
              const binding = settings.roles[role.key] ?? { provider: "deepseek", model: "" };
              const provider = catalog.chat.find((p) => p.key === binding.provider);
              // 自定义端点的模型候选来自 custom_chat（用户填写），其余来自 catalog。
              // custom（本机 Ollama）与 openai_compat（中转）后端都走 custom_chat 覆盖
              const modelOptions = CUSTOM_PROVIDERS.has(binding.provider)
                ? settings.custom_chat.models
                : (provider?.models ?? []);
              const id = `role:${role.key}`;
              const state = probe[id];
              return (
                <div key={role.key} className="space-y-1.5">
                  <Label className="text-sm">{role.label}</Label>
                  <div className="flex flex-wrap gap-2">
                    <Select
                      value={binding.provider}
                      onValueChange={(v) => {
                        const next = catalog.chat.find((p) => p.key === v);
                        // 自定义端点直接用 catalog 默认值（空串 / qwen3:14b）多半不在
                        // 用户自己的清单里，切过去时优先带出用户填的第一个模型
                        const fallback = CUSTOM_PROVIDERS.has(v)
                          ? (settings.custom_chat.models[0] ?? next?.default_model ?? "")
                          : (next?.default_model ?? "");
                        setSettings({
                          ...settings,
                          roles: {
                            ...settings.roles,
                            [role.key]: { provider: v, model: fallback },
                          },
                        });
                      }}
                    >
                      <SelectTrigger className="w-[190px]">
                        <SelectValue />
                      </SelectTrigger>
                      <SelectContent>
                        {catalog.chat.map((p) => (
                          <SelectItem key={p.key} value={p.key}>
                            {p.label}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                    <ModelCombobox
                      value={binding.model}
                      options={modelOptions}
                      placeholder="输入或选择模型"
                      onChange={(v) =>
                        setSettings({
                          ...settings,
                          roles: {
                            ...settings.roles,
                            [role.key]: { ...binding, model: v.trim() },
                          },
                        })
                      }
                    />
                    <Button
                      variant="outline"
                      disabled={state?.pending}
                      onClick={() =>
                        void runProbe(id, {
                          provider_key: binding.provider,
                          model: binding.model || provider?.default_model || "",
                          // 把界面上的草稿地址一并带过去，测试的才是眼前这份配置
                          base_url: CUSTOM_PROVIDERS.has(binding.provider)
                            ? settings.custom_chat.base_url
                            : "",
                        })
                      }
                    >
                      {state?.pending ? <Loader2 className="animate-spin" /> : null}
                      测试
                    </Button>
                  </div>
                  {state?.result && <ProbeLine result={state.result} />}
                </div>
              );
            })}
          </CardContent>
        </Card>

        </TabsContent>

        <TabsContent value="realtime" className="mt-6">
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2 text-base">
              <AudioLines className="text-interviewer size-4" />
              实时语音
            </CardTitle>
            <CardDescription>面试官的声音由这里的模型生成，语义打断依赖它的能力</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {(() => {
              // 实时语音的 Key 与文本模型共用凭据库，但用户在配语音时
              // 不该被迫切回「模型选择」标签找输入框——就在这页给一份同款
              const credKey = realtime?.credential_key ?? "";
              const chatOwner = catalog.chat.find((p) => p.key === credKey);
              const state = probe[`key:${credKey}`];
              return (
                <div className="space-y-1.5">
                  <div className="flex items-center justify-between gap-2">
                    <Label className="text-sm">
                      API Key
                      {chatOwner && (
                        <span className="text-muted-foreground ml-1.5 text-xs font-normal">
                          与「{chatOwner.label}」共用同一把 Key，任一处填写即可
                        </span>
                      )}
                    </Label>
                    <div className="flex items-center gap-2">
                      {keyPresent[credKey] && !keyDraft[credKey] && (
                        <span className="text-success flex items-center gap-1 text-xs">
                          <Check className="size-3" />
                          已配置
                        </span>
                      )}
                      {realtime?.console_url && (
                        <button
                          type="button"
                          onClick={() => void openConsole(realtime.console_url)}
                          className="text-muted-foreground hover:text-foreground flex items-center gap-1 text-xs transition-colors"
                        >
                          获取
                          <ExternalLink className="size-3" />
                        </button>
                      )}
                    </div>
                  </div>
                  <div className="flex gap-2">
                    <div className="relative flex-1">
                      <Input
                        type={reveal[credKey] ? "text" : "password"}
                        placeholder={
                          keyPresent[credKey] ? "已保存，留空表示不修改" : "粘贴 API Key"
                        }
                        value={keyDraft[credKey] ?? ""}
                        onChange={(e) =>
                          setKeyDraft((prev) => ({ ...prev, [credKey]: e.target.value }))
                        }
                        className="pr-9"
                      />
                      <button
                        type="button"
                        onClick={() => setReveal((prev) => ({ ...prev, [credKey]: !prev[credKey] }))}
                        className="text-muted-foreground hover:text-foreground absolute top-1/2 right-2 -translate-y-1/2"
                        aria-label={reveal[credKey] ? "隐藏" : "显示"}
                      >
                        {reveal[credKey] ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
                      </button>
                    </div>
                    <Button
                      variant="outline"
                      disabled={state?.pending || (!keyPresent[credKey] && !keyDraft[credKey]?.trim())}
                      onClick={() =>
                        void runProbe(`key:${credKey}`, {
                          provider_key: credKey,
                          api_key: keyDraft[credKey]?.trim() ?? "",
                        })
                      }
                    >
                      {state?.pending ? <Loader2 className="animate-spin" /> : null}
                      测试
                    </Button>
                  </div>
                  {state?.result && <ProbeLine result={state.result} />}
                </div>
              );
            })()}
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="供应商">
                <Select
                  value={settings.realtime.provider}
                  onValueChange={(v) => {
                    const next = catalog.realtime.find((p) => p.key === v);
                    setSettings({
                      ...settings,
                      realtime: {
                        ...settings.realtime,
                        provider: v,
                        model: next?.default_model ?? "",
                        voice: next?.voices[0]?.value ?? "",
                      },
                    });
                  }}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {catalog.realtime.map((p) => (
                      <SelectItem key={p.key} value={p.key}>
                        {p.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field label="模型">
                <Select
                  value={settings.realtime.model || (realtime?.default_model ?? "")}
                  onValueChange={(v) =>
                    setSettings({ ...settings, realtime: { ...settings.realtime, model: v } })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {/* 已保存的模型名不在候选里时也要如实显示，否则界面空白、值却存在 */}
                    {(() => {
                      const models = realtime?.models ?? [];
                      const current = settings.realtime.model || (realtime?.default_model ?? "");
                      const items =
                        current && !models.includes(current) ? [current, ...models] : models;
                      return items.map((m) => (
                        <SelectItem key={m} value={m}>
                          {m}
                        </SelectItem>
                      ));
                    })()}
                  </SelectContent>
                </Select>
              </Field>
              <Field label="默认音色">
                <Select
                  value={settings.realtime.voice || (realtime?.voices[0]?.value ?? "")}
                  onValueChange={(v) =>
                    setSettings({ ...settings, realtime: { ...settings.realtime, voice: v } })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {(realtime?.voices ?? []).map((v) => (
                      <SelectItem key={v.value} value={v.value}>
                        {v.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field label={`语气随机度 ${settings.realtime.temperature.toFixed(2)}`}>
                <Slider
                  min={0.1}
                  max={1.5}
                  step={0.05}
                  value={[settings.realtime.temperature]}
                  onValueChange={([v]) =>
                    setSettings({
                      ...settings,
                      realtime: { ...settings.realtime, temperature: v },
                    })
                  }
                />
              </Field>
            </div>
            <div>
              <Button
                variant="outline"
                disabled={probe["rt"]?.pending}
                onClick={() =>
                  void runProbe("rt", {
                    provider_key: settings.realtime.provider,
                    model: settings.realtime.model,
                    realtime: true,
                  })
                }
              >
                {probe["rt"]?.pending ? <Loader2 className="animate-spin" /> : null}
                测试实时语音连通
              </Button>
              {probe["rt"]?.result && <ProbeLine result={probe["rt"].result} className="mt-2" />}
            </div>
          </CardContent>
        </Card>

        </TabsContent>

        <TabsContent value="audio" className="mt-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">音频</CardTitle>
            <CardDescription>
              强烈建议戴耳机。外放时回声门控会持续工作，但仍不如物理隔离可靠
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="麦克风">
                <Select
                  value={settings.audio.input_device || "__default__"}
                  onValueChange={(v) =>
                    setSettings({
                      ...settings,
                      audio: { ...settings.audio, input_device: v === "__default__" ? "" : v },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__default__">系统默认</SelectItem>
                    {devices.inputs.map((d) => (
                      <SelectItem key={d.index} value={d.name}>
                        {d.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field label="扬声器">
                <Select
                  value={settings.audio.output_device || "__default__"}
                  onValueChange={(v) =>
                    setSettings({
                      ...settings,
                      audio: { ...settings.audio, output_device: v === "__default__" ? "" : v },
                    })
                  }
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="__default__">系统默认</SelectItem>
                    {devices.outputs.map((d) => (
                      <SelectItem key={d.index} value={d.name}>
                        {d.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
              <Field label={`人声灵敏度 ${settings.audio.vad_threshold.toFixed(2)}`}>
                <Slider
                  min={0.05}
                  max={0.95}
                  step={0.01}
                  value={[settings.audio.vad_threshold]}
                  onValueChange={([v]) =>
                    setSettings({ ...settings, audio: { ...settings.audio, vad_threshold: v } })
                  }
                />
              </Field>
              <Field label={`断句静音时长 ${settings.audio.silence_duration_ms} ms`}>
                <Slider
                  min={200}
                  max={2000}
                  step={20}
                  value={[settings.audio.silence_duration_ms]}
                  onValueChange={([v]) =>
                    setSettings({
                      ...settings,
                      audio: { ...settings.audio, silence_duration_ms: v },
                    })
                  }
                />
              </Field>
            </div>
            <ToggleRow
              label="语义打断"
              hint="区分「嗯嗯」这类附和与真正的插话"
              checked={settings.audio.semantic_vad}
              onChange={(v) =>
                setSettings({ ...settings, audio: { ...settings.audio, semantic_vad: v } })
              }
            />
            <ToggleRow
              label="自动增益"
              hint="按说话音量自动调整采集增益，静音时不放大底噪"
              checked={settings.audio.auto_gain}
              onChange={(v) =>
                setSettings({ ...settings, audio: { ...settings.audio, auto_gain: v } })
              }
            />
          </CardContent>
        </Card>

        </TabsContent>

        <TabsContent value="features" className="mt-6">
        <Card>
          <CardHeader>
            <CardTitle className="text-base">功能</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <ToggleRow
              label="实时提词"
              hint="卡壳时给关键词与展开方向"
              checked={settings.features.copilot_enabled}
              onChange={(v) =>
                setSettings({ ...settings, features: { ...settings.features, copilot_enabled: v } })
              }
            />
            <ToggleRow
              label="代码环节"
              hint="技术岗面试中插入手写代码与讲思路"
              checked={settings.features.coding_round_enabled}
              onChange={(v) =>
                setSettings({
                  ...settings,
                  features: { ...settings.features, coding_round_enabled: v },
                })
              }
            />
            <ToggleRow
              label="保存录音"
              hint="留在本机，用于复盘时回听"
              checked={settings.features.save_audio}
              onChange={(v) =>
                setSettings({ ...settings, features: { ...settings.features, save_audio: v } })
              }
            />
          </CardContent>
        </Card>
        </TabsContent>
      </Tabs>

      <button
        type="button"
        onClick={onOpenAbout}
        className="hover:bg-accent/50 mt-5 flex w-full items-center gap-3 rounded-xl border px-5 py-4 text-left transition-colors duration-150 ease-out"
      >
        <Info className="text-muted-foreground size-4 shrink-0" />
        <span className="min-w-0 flex-1">
          <span className="block text-[13.5px] font-medium">关于这个项目</span>
          <span className="text-muted-foreground mt-0.5 block text-xs">
            开源地址、联系方式，以及我做这个东西的原因
          </span>
        </span>
        <ChevronRight className="text-muted-foreground size-4 shrink-0" />
      </button>
    </PageContainer>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label className="text-muted-foreground text-xs font-normal">{label}</Label>
      {children}
    </div>
  );
}

function ToggleRow({
  label,
  hint,
  checked,
  onChange,
}: {
  label: string;
  hint: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <div className="flex items-center justify-between gap-4">
      <div className="min-w-0">
        <p className="text-sm font-medium">{label}</p>
        <p className="text-muted-foreground text-xs">{hint}</p>
      </div>
      <Switch checked={checked} onCheckedChange={onChange} />
    </div>
  );
}

function ProbeLine({ result, className }: { result: ProbeOutcome; className?: string }) {
  return (
    <p
      className={cn(
        "flex items-center gap-1.5 text-xs",
        result.ok ? "text-success" : "text-destructive",
        className,
      )}
    >
      {result.ok ? <Check className="size-3.5 shrink-0" /> : <X className="size-3.5 shrink-0" />}
      <span className="selectable">{result.detail}</span>
    </p>
  );
}

/** 可输入的模型选择框：既能在候选里挑，也能直接敲任意模型名。
 *
 *  之前的封闭下拉有个隐蔽缺陷：选自定义供应商后界面显示的是列表第一项，
 *  实际保存的却是 catalog 的默认模型（openai_compat 是空串、custom 是 qwen3:14b），
 *  所见非所得。输入框直接展示真实保存值，从根上消除这个错位。
 */
function ModelCombobox({
  value,
  options,
  placeholder,
  onChange,
}: {
  value: string;
  options: string[];
  placeholder: string;
  onChange: (v: string) => void;
}) {
  const [open, setOpen] = useState(false);
  // 过滤用的搜索词与已绑定值是两回事：绑定值是 gpt-5.6-terra 时，
  // 展开面板应当显示全部候选，而不是拿绑定值过滤到只剩自己
  const [query, setQuery] = useState("");
  const matches = query ? options.filter((m) => m.toLowerCase().includes(query)) : options;
  return (
    <div className="relative min-w-[210px] flex-1">
      <Input
        value={value}
        placeholder={placeholder}
        role="combobox"
        aria-expanded={open}
        className="font-mono text-xs"
        onChange={(e) => {
          onChange(e.target.value);
          setQuery(e.target.value);
          setOpen(true);
        }}
        onFocus={() => {
          setOpen(true);
          setQuery("");
        }}
        onBlur={() => setOpen(false)}
        onKeyDown={(e) => {
          if (e.key === "Escape" || e.key === "Enter") {
            e.preventDefault();
            setOpen(false);
          }
        }}
      />
      {/* 面板按下时不抢走输入框焦点，否则 blur 先关闭面板、点击永远落空 */}
      {open && matches.length > 0 && (
        <div
          className="bg-popover text-popover-foreground absolute top-full left-0 z-50 mt-1 max-h-64 w-full overflow-auto rounded-md border shadow-md"
          onMouseDown={(e) => e.preventDefault()}
        >
          {matches.map((m) => (
            <button
              key={m}
              type="button"
              className={cn(
                "flex w-full items-center gap-2 px-3 py-1.5 text-left font-mono text-xs",
                m === value ? "bg-accent" : "hover:bg-accent/50",
              )}
              onClick={() => {
                onChange(m);
                setQuery("");
                setOpen(false);
              }}
            >
              {m === value && <Check className="size-3 shrink-0" />}
              <span className="truncate">{m}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
