/**
 * Minimal schema validation (Zod-like API)
 * Inspired by schemastery/zod but simplified for browser
 */

export type ZodTypeAny = ZodSchema<any>;

export interface ZodSchema<T> {
  parse(data: unknown): T;
  safeParse(data: unknown): { success: true; data: T } | { success: false; error: ZodError };
  optional(): ZodSchema<T | undefined>;
  nullable(): ZodSchema<T | null>;
  default(def: T): ZodSchema<T>;
  describe(description: string): ZodSchema<T>;
}

export interface ZodError {
  issues: ZodIssue[];
  message: string;
}

export interface ZodIssue {
  code: string;
  path: (string | number)[];
  message: string;
}

type ParseResult<T> = { success: true; data: T } | { success: false; error: ZodError };

function createSchema<T>(parse: (data: unknown) => T, typeName: string): ZodSchema<T> {
  const schema: ZodSchema<T> = {
    parse(data: unknown): T {
      const result = safeParse(data);
      if (!result.success) throw new Error(result.error.message);
      return result.data;
    },
    safeParse(data: unknown): ParseResult<T> {
      try {
        return { success: true, data: parse(data) };
      } catch (e: any) {
        return { success: false, error: { issues: [{ code: 'custom', path: [], message: e.message }], message: e.message } };
      }
    },
    optional() { return createSchema<T | undefined>((d) => d === undefined ? undefined : parse(d), `${typeName}?`); },
    nullable() { return createSchema<T | null>((d) => d === null ? null : parse(d), `${typeName} | null`); },
    default(def: T) { return createSchema<T>((d) => d === undefined ? def : parse(d), typeName); },
    describe(_desc: string) { return schema; },
  };
  return schema;
}

export const z = {
  string(): ZodSchema<string> {
    return createSchema<string>((d) => {
      if (typeof d !== 'string') throw new Error('Expected string');
      return d;
    }, 'string');
  },
  number(): ZodSchema<number> {
    return createSchema<number>((d) => {
      if (typeof d !== 'number' || isNaN(d)) throw new Error('Expected number');
      return d;
    }, 'number');
  },
  boolean(): ZodSchema<boolean> {
    return createSchema<boolean>((d) => {
      if (typeof d !== 'boolean') throw new Error('Expected boolean');
      return d;
    }, 'boolean');
  },
  any(): ZodSchema<any> {
    return createSchema<any>((d) => d, 'any');
  },
  null(): ZodSchema<null> {
    return createSchema<null>((d) => {
      if (d !== null) throw new Error('Expected null');
      return null;
    }, 'null');
  },
  undefined(): ZodSchema<undefined> {
    return createSchema<undefined>((d) => {
      if (d !== undefined) throw new Error('Expected undefined');
      return undefined;
    }, 'undefined');
  },
  object<T extends Record<string, ZodSchema<any>>>(shape: T): ZodSchema<{ [K in keyof T]: T[K] extends ZodSchema<infer U> ? U : never }> {
    return createSchema((d) => {
      if (typeof d !== 'object' || d === null || Array.isArray(d)) throw new Error('Expected object');
      const result: any = {};
      for (const [key, schema] of Object.entries(shape)) {
        const value = (d as any)[key];
        result[key] = schema.parse(value);
      }
      return result;
    }, 'object');
  },
  array<T>(item: ZodSchema<T>): ZodSchema<T[]> {
    return createSchema<T[]>((d) => {
      if (!Array.isArray(d)) throw new Error('Expected array');
      return d.map((v, i) => item.parse(v));
    }, 'array');
  },
  union<T extends ZodSchema<any>[]>(...types: T): ZodSchema<T[number] extends ZodSchema<infer U> ? U : never> {
    return createSchema((d) => {
      for (const schema of types) {
        const result = schema.safeParse(d);
        if (result.success) return result.data;
      }
      throw new Error('No matching union type');
    }, 'union');
  },
  enum<T extends string>(values: T[]): ZodSchema<T> {
    return createSchema<T>((d) => {
      if (!values.includes(d as T)) throw new Error(`Expected one of ${values.join(', ')}`);
      return d as T;
    }, 'enum');
  },
  literal<T extends string | number | boolean>(value: T): ZodSchema<T> {
    return createSchema<T>((d) => {
      if (d !== value) throw new Error(`Expected literal ${value}`);
      return value;
    }, 'literal');
  },
  function(): ZodSchema<Function> {
    return createSchema<Function>((d) => {
      if (typeof d !== 'function') throw new Error('Expected function');
      return d;
    }, 'function');
  },
  promise<T>(item: ZodSchema<T>): ZodSchema<Promise<T>> {
    return createSchema<Promise<T>>((d) => {
      if (!(d instanceof Promise)) throw new Error('Expected Promise');
      return d.then(item.parse);
    }, 'promise');
  },
  record<T>(key: ZodSchema<string>, value: ZodSchema<T>): ZodSchema<Record<string, T>> {
    return createSchema<Record<string, T>>((d) => {
      if (typeof d !== 'object' || d === null || Array.isArray(d)) throw new Error('Expected record');
      const result: Record<string, T> = {};
      for (const [k, v] of Object.entries(d)) {
        result[key.parse(k)] = value.parse(v);
      }
      return result;
    }, 'record');
  },
  tuple<T extends ZodSchema<any>[]>(...items: T): ZodSchema<{ [K in keyof T]: T[K] extends ZodSchema<infer U> ? U : never }> {
    return createSchema((d) => {
      if (!Array.isArray(d) || d.length !== items.length) throw new Error('Expected tuple of correct length');
      return d.map((v, i) => items[i].parse(v));
    }, 'tuple');
  },
};

export type infer<T extends ZodSchema<any>> = T extends ZodSchema<infer U> ? U : never;
