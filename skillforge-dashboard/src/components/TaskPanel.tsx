import React, { useEffect, useMemo, useState } from 'react';
import type { Task, TaskState } from '../contexts/TaskTrackerContext';
import { useTaskTracker } from '../contexts/TaskTrackerContext';
import './TaskPanel.css';

const stateIcon: Record<TaskState, string> = {
  running: '⟳',
  success: '✓',
  failed: '✕',
  info: 'ⓘ',
};

const stateLabel: Record<TaskState, string> = {
  running: 'Running',
  success: 'Done',
  failed: 'Failed',
  info: 'Info',
};

const formatDuration = (start: number, end?: number): string => {
  const ms = (end ?? Date.now()) - start;
  if (ms < 1000) return `${ms}ms`;
  const s = Math.round(ms / 100) / 10;
  if (s < 60) return `${s}s`;
  const m = Math.floor(s / 60);
  const rem = Math.round(s - m * 60);
  return `${m}m${rem ? ` ${rem}s` : ''}`;
};

const TaskRow: React.FC<{ task: Task; onDismiss: (id: string) => void }> = ({
  task,
  onDismiss,
}) => {
  // Re-render running tasks every second so the duration ticks.
  const [, force] = useState(0);
  useEffect(() => {
    if (task.state !== 'running') return;
    const t = setInterval(() => force((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [task.state]);

  return (
    <li className={`tp-task tp-task--${task.state}`}>
      <span className={`tp-icon tp-icon--${task.state}`} aria-hidden>
        {stateIcon[task.state]}
      </span>
      <div className="tp-body">
        <div className="tp-label">{task.label}</div>
        {task.detail && <div className="tp-detail">{task.detail}</div>}
        <div className="tp-meta">
          <span>{stateLabel[task.state]}</span>
          <span>·</span>
          <span>{formatDuration(task.startedAt, task.finishedAt)}</span>
        </div>
      </div>
      {task.state !== 'running' && (
        <button
          type="button"
          className="tp-dismiss"
          aria-label="Dismiss"
          onClick={() => onDismiss(task.id)}
        >
          ×
        </button>
      )}
    </li>
  );
};

const TaskPanel: React.FC = () => {
  const { tasks, dismissTask, clearAll } = useTaskTracker();
  const [expanded, setExpanded] = useState(false);

  const runningCount = useMemo(
    () => tasks.filter((t) => t.state === 'running').length,
    [tasks],
  );
  const failedCount = useMemo(
    () => tasks.filter((t) => t.state === 'failed').length,
    [tasks],
  );

  // Auto-expand the first time something starts running; collapse once
  // everything is dismissed.
  useEffect(() => {
    if (tasks.length === 0) {
      setExpanded(false);
    } else if (runningCount > 0) {
      setExpanded(true);
    }
  }, [tasks.length, runningCount]);

  if (tasks.length === 0) return null;

  return (
    <aside
      className={`tp-root ${expanded ? 'tp-root--open' : 'tp-root--collapsed'}`}
      role="status"
      aria-label="Background tasks"
    >
      <button
        type="button"
        className="tp-header"
        onClick={() => setExpanded((v) => !v)}
        aria-expanded={expanded}
      >
        <span className={`tp-pulse ${runningCount > 0 ? 'tp-pulse--on' : ''}`} />
        <span className="tp-title">
          {runningCount > 0
            ? `${runningCount} task${runningCount > 1 ? 's' : ''} running`
            : failedCount > 0
              ? `${failedCount} failed`
              : 'Recent tasks'}
        </span>
        <span className="tp-count">{tasks.length}</span>
        <span className="tp-caret">{expanded ? '▾' : '▴'}</span>
      </button>
      {expanded && (
        <>
          <ul className="tp-list">
            {tasks.map((t) => (
              <TaskRow key={t.id} task={t} onDismiss={dismissTask} />
            ))}
          </ul>
          <div className="tp-footer">
            <button
              type="button"
              className="tp-clear"
              onClick={clearAll}
              disabled={runningCount > 0}
              title={
                runningCount > 0
                  ? 'Wait for running tasks to finish'
                  : 'Clear all'
              }
            >
              Clear all
            </button>
          </div>
        </>
      )}
    </aside>
  );
};

export default TaskPanel;
