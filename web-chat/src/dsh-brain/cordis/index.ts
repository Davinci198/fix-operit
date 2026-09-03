/**
 * Minimal Cordis-like plugin system for browser
 * Inspired by @deepseek-ai/cordis but simplified
 */

export interface Service<T = any> {
  new (...args: any[]): T;
}

export interface Plugin {
  name: string;
  apply(ctx: Context): void | (() => void) | Promise<void | (() => void)>;
  services?: Service[];
  dependencies?: string[];
}

export interface Context {
  plugin(name: string): Plugin | undefined;
  service<T>(name: string): T | undefined;
  register<T>(service: Service<T>, instance?: T): T;
  use(plugin: Plugin): () => void;
  dispose(): void;
}

interface ServiceEntry<T> {
  ctor: Service<T>;
  instance: T | null;
  pluginName: string;
}

interface PluginEntry {
  plugin: Plugin;
  dispose?: () => void;
}

export function createContext(): Context {
  const services = new Map<string, ServiceEntry<any>>();
  const plugins = new Map<string, PluginEntry>();
  const pluginOrder: string[] = [];

  function getServiceName(ctor: Service): string {
    return ctor.name;
  }

  return {
    plugin(name: string) {
      return plugins.get(name)?.plugin;
    },

    service<T>(name: string): T | undefined {
      const entry = services.get(name);
      if (!entry) return undefined;
      if (!entry.instance) {
        entry.instance = new entry.ctor();
      }
      return entry.instance;
    },

    register<T>(ctor: Service<T>, instance?: T): T {
      const name = getServiceName(ctor);
      const existing = services.get(name);
      if (existing) {
        if (instance) existing.instance = instance;
        return existing.instance!;
      }
      const entry: ServiceEntry<T> = {
        ctor,
        instance: instance ?? null,
        pluginName: '',
      };
      services.set(name, entry);
      if (!instance) {
        entry.instance = new ctor();
      }
      return entry.instance!;
    },

    use(plugin: Plugin) {
      if (plugins.has(plugin.name)) {
        throw new Error(`Plugin ${plugin.name} already registered`);
      }

      // Check dependencies
      for (const dep of plugin.dependencies || []) {
        if (!plugins.has(dep)) {
          throw new Error(`Plugin ${plugin.name} requires dependency ${dep}`);
        }
      }

      const ctx = createContextProxy(plugin.name);
      const dispose = plugin.apply(ctx);
      
      const entry: PluginEntry = { plugin, dispose: dispose as any };
      plugins.set(plugin.name, entry);
      pluginOrder.push(plugin.name);

      return () => {
        const p = plugins.get(plugin.name);
        if (p?.dispose) p.dispose();
        plugins.delete(plugin.name);
        const idx = pluginOrder.indexOf(plugin.name);
        if (idx >= 0) pluginOrder.splice(idx, 1);
      };
    },

    dispose() {
      // Dispose in reverse order
      for (const name of [...pluginOrder].reverse()) {
        const p = plugins.get(name);
        if (p?.dispose) p.dispose();
      }
      plugins.clear();
      services.clear();
      pluginOrder.length = 0;
    },
  };

  function createContextProxy(currentPlugin: string): Context {
    return {
      plugin(name: string) {
        return plugins.get(name)?.plugin;
      },
      service<T>(name: string): T | undefined {
        const entry = services.get(name);
        if (!entry) return undefined;
        if (!entry.instance) {
          entry.instance = new entry.ctor();
        }
        return entry.instance;
      },
      register<T>(ctor: Service<T>, instance?: T): T {
        const name = getServiceName(ctor);
        const existing = services.get(name);
        if (existing) {
          if (instance) existing.instance = instance;
          return existing.instance!;
        }
        const entry: ServiceEntry<T> = {
          ctor,
          instance: instance ?? null,
          pluginName: currentPlugin,
        };
        services.set(name, entry);
        if (!instance) {
          entry.instance = new ctor();
        }
        return entry.instance!;
      },
      use(plugin: Plugin) {
        return createContext().use(plugin);
      },
      dispose() {
        // No-op for proxy
      },
    };
  }
}

// Helper to define plugins easily
export function definePlugin(plugin: Plugin): Plugin {
  return plugin;
}

// Service decorator helper
export function service<T>(ctor: Service<T>): Service<T> {
  return ctor;
}
