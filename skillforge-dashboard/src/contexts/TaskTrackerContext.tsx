import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';

export type TaskState = 'running' | 'success' | 'failed' | 'info';
export type TaskType =
  | 'skill-extract'
  | 'skill-ab-eval'
  | 'memory-consolidation'
  | 'other';

export interface Task {
  id: string;
  type: TaskType;
  label: string;
  state: TaskState;
  detail?: string;
  relatedId?: string;
  startedAt: number;
  finishedAt?: number;
}

interface TaskTrackerCtx {
  tasks: Task[];
  addTask: (task: Omit<Task, 'startedAt'>) => string;
  updateTask: (id: string, patch: Partial<Task>) => void;
  resolveByMatch: (
    type: TaskType,
    matcher: (task: Task) => boolean,
    patch: Partial<Task>,
  ) => boolean;
  dismissTask: (id: string) => void;
  clearAll: () => void;
}

const TaskTrackerContext = createContext<TaskTrackerCtx | null>(null);

const TERMINAL_TTL_MS = 8000;

export const TaskTrackerProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [tasks, setTasks] = useState<Task[]>([]);
  const timersRef = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    tasks.forEach((t) => {
      if (t.state !== 'running' && !timersRef.current.has(t.id)) {
        const id = t.id;
        const handle = setTimeout(() => {
          setTasks((prev) => prev.filter((x) => x.id !== id));
          timersRef.current.delete(id);
        }, TERMINAL_TTL_MS);
        timersRef.current.set(id, handle);
      }
    });
  }, [tasks]);

  useEffect(() => {
    const timers = timersRef.current;
    return () => {
      timers.forEach((t) => clearTimeout(t));
      timers.clear();
    };
  }, []);

  const addTask = useCallback((task: Omit<Task, 'startedAt'>) => {
    setTasks((prev) => [...prev, { ...task, startedAt: Date.now() }]);
    return task.id;
  }, []);

  const updateTask = useCallback((id: string, patch: Partial<Task>) => {
    setTasks((prev) => prev.map((t) => (t.id === id ? { ...t, ...patch } : t)));
  }, []);

  const resolveByMatch = useCallback(
    (
      type: TaskType,
      matcher: (task: Task) => boolean,
      patch: Partial<Task>,
    ): boolean => {
      let hit = false;
      setTasks((prev) => {
        const target = prev.find(
          (t) => t.type === type && t.state === 'running' && matcher(t),
        );
        if (!target) return prev;
        hit = true;
        return prev.map((t) =>
          t.id === target.id
            ? { ...t, ...patch, finishedAt: Date.now() }
            : t,
        );
      });
      return hit;
    },
    [],
  );

  const dismissTask = useCallback((id: string) => {
    const timer = timersRef.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timersRef.current.delete(id);
    }
    setTasks((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const clearAll = useCallback(() => {
    timersRef.current.forEach((t) => clearTimeout(t));
    timersRef.current.clear();
    setTasks([]);
  }, []);

  const value = useMemo<TaskTrackerCtx>(
    () => ({ tasks, addTask, updateTask, resolveByMatch, dismissTask, clearAll }),
    [tasks, addTask, updateTask, resolveByMatch, dismissTask, clearAll],
  );

  return (
    <TaskTrackerContext.Provider value={value}>
      {children}
    </TaskTrackerContext.Provider>
  );
};

export const useTaskTracker = (): TaskTrackerCtx => {
  const v = useContext(TaskTrackerContext);
  if (!v) {
    throw new Error('useTaskTracker must be used inside TaskTrackerProvider');
  }
  return v;
};

export const newTaskId = (): string =>
  `task-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
