/**
 * A REST list with "load older" paging (`before=<oldest ts>`) merged with live items from the
 * socket store, newest first.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { errorMessage, isAbort } from '../lib/api';
import { useConn } from '../store/conn';

export interface PagedListOptions<T> {
  fetchPage: (before: string | undefined, signal: AbortSignal) => Promise<T[]>;
  /** Changing this string resets the list (filters, device). */
  resetKey: string;
  live: T[];
  keyOf: (x: T) => string;
  timeOf: (x: T) => string;
  accept?: (x: T) => boolean;
  pageSize: number;
}

export function usePagedList<T>({ fetchPage, resetKey, live, keyOf, timeOf, accept, pageSize }: PagedListOptions<T>) {
  const epoch = useConn((s) => s.epoch);
  const [pages, setPages] = useState<T[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(true);
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const ctrlRef = useRef<AbortController | null>(null);

  useEffect(() => {
    ctrlRef.current?.abort();
    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    setLoading(true);
    setError(null);
    fetchRef
      .current(undefined, ctrl.signal)
      .then((list) => {
        if (ctrl.signal.aborted) return;
        setPages(Array.isArray(list) ? list : []);
        setHasMore((list?.length ?? 0) >= pageSize);
      })
      .catch((e: unknown) => {
        if (!isAbort(e) && !ctrl.signal.aborted) setError(errorMessage(e));
      })
      .finally(() => {
        if (!ctrl.signal.aborted) setLoading(false);
      });
    return () => ctrl.abort();
  }, [resetKey, epoch, pageSize]);

  const items = useMemo(() => {
    const map = new Map<string, T>();
    for (const x of pages) map.set(keyOf(x), x);
    for (const x of live) if (!accept || accept(x)) map.set(keyOf(x), x);
    return [...map.values()].sort((a, b) => Date.parse(timeOf(b)) - Date.parse(timeOf(a)));
    // keyOf/timeOf/accept are expected to be pure; resetKey covers filter changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pages, live, resetKey]);

  const loadMore = useCallback(async () => {
    const oldest = items[items.length - 1];
    if (!oldest) return;
    const ctrl = new AbortController();
    ctrlRef.current = ctrl;
    setLoading(true);
    setError(null);
    try {
      const more = await fetchRef.current(timeOf(oldest), ctrl.signal);
      setPages((p) => [...p, ...(Array.isArray(more) ? more : [])]);
      setHasMore((more?.length ?? 0) >= pageSize);
    } catch (e) {
      if (!isAbort(e)) setError(errorMessage(e));
    } finally {
      setLoading(false);
    }
  }, [items, timeOf, pageSize]);

  return { items, loading, error, hasMore, loadMore };
}
