import { useEffect } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { notification } from 'antd'
import { useAuthStore } from '@/store/auth'

/**
 * Keeps the task list current when the other party acts.
 *
 * <p><b>The event carries no data, and that is the point.</b> The server says
 * only "your pending work changed"; this hook refetches. Sending the task
 * itself would mean the server deciding what counts as pending in a second
 * place, and the screen and the AI advisor would eventually disagree about it.
 *
 * <p>Why a stream rather than polling: the thing being waited on has a
 * deadline. An acceptance the lister has not noticed is an acceptance that
 * expires, and the whole complaint that led here was a seller discovering a
 * sale too late. A poll would make the delay a design choice; this makes it a
 * network round trip.
 *
 * <p>The token goes in the query string because `EventSource` cannot set
 * headers — the same trade-off, and the same mitigation, as the market feed:
 * the server accepts a query token on a named list of stream paths and nowhere
 * else.
 */
export function useTaskStream() {
  const accessToken = useAuthStore((state) => state.accessToken)
  const queryClient = useQueryClient()

  useEffect(() => {
    if (!accessToken) return

    const source = new EventSource(`/api/tasks/stream?token=${encodeURIComponent(accessToken)}`)

    source.addEventListener('tasks', (event) => {
      void queryClient.invalidateQueries({ queryKey: ['tasks'] })

      // Anything that can change the task list can also change the lists it was
      // derived from, so those are refreshed too rather than waiting for the
      // user to navigate.
      void queryClient.invalidateQueries({ queryKey: ['my-orders'] })
      void queryClient.invalidateQueries({ queryKey: ['my-listings'] })
      void queryClient.invalidateQueries({ queryKey: ['inventory-notes'] })
      void queryClient.invalidateQueries({ queryKey: ['market-quotes'] })

      const reason = readReason(event)
      if (reason) {
        notification.info({
          message: '有新的待办',
          description: reason,
          placement: 'bottomRight',
          duration: 4,
        })
      }
    })

    source.onerror = () => {
      // EventSource retries on its own; the poll interval on the task query is
      // the fallback for a server that cannot hold the connection open.
    }

    return () => source.close()
  }, [accessToken, queryClient])
}

/**
 * Reads the reason out of the event, tolerating anything unexpected.
 *
 * <p>A malformed frame should cost a toast, not the notification. The refetch
 * above is the part that matters; the message is a courtesy.
 */
function readReason(event: Event): string | null {
  try {
    const payload = JSON.parse((event as MessageEvent).data) as { reason?: string }
    return payload.reason ?? null
  } catch {
    return null
  }
}
