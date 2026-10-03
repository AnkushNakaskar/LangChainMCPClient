/**
 * Minimal Server-Sent Events client for the chat endpoint.
 *
 * <p>`EventSource` cannot be used here: it only issues GET requests, while a prompt plus its
 * session and tool settings is a POST body. `fetch` exposes the response as a stream, so the
 * frames are read and parsed as they arrive instead of waiting for the body to complete.
 */

const FRAME_SEPARATOR = '\n\n';

/**
 * Parses one SSE frame. Comment lines, which the server sends as a keep-alive, carry no payload
 * and are skipped.
 */
function parseFrame(frame) {
  let eventName = 'message';
  const data = [];

  for (const line of frame.split('\n')) {
    if (line === '' || line.startsWith(':')) {
      continue;
    }
    if (line.startsWith('event:')) {
      eventName = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice('data:'.length).replace(/^ /, ''));
    }
  }

  if (data.length === 0) {
    return null;
  }
  try {
    return { event: eventName, payload: JSON.parse(data.join('\n')) };
  } catch (error) {
    console.warn('Discarding an unreadable SSE frame', frame, error);
    return null;
  }
}

/**
 * Posts a prompt and calls `onEvent` for every frame until the stream ends.
 *
 * @param {object} request  the chat request body
 * @param {(event: object) => void} onEvent  receives each parsed event payload
 * @param {AbortSignal} signal  aborts the request when the user stops the answer
 */
export async function streamChat(request, onEvent, signal) {
  const response = await fetch('/langchain/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(request),
    signal,
  });

  if (!response.ok) {
    throw new Error(`The server answered ${response.status} ${response.statusText}`);
  }
  if (!response.body) {
    throw new Error('This browser cannot read a streamed response');
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  try {
    for (;;) {
      const { value, done } = await reader.read();
      if (done) {
        break;
      }
      // a frame can be split across chunks, so decoding stays in streaming mode and the
      // remainder is kept until its separator arrives
      buffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, '\n');

      let separator = buffer.indexOf(FRAME_SEPARATOR);
      while (separator >= 0) {
        const frame = buffer.slice(0, separator);
        buffer = buffer.slice(separator + FRAME_SEPARATOR.length);
        const parsed = parseFrame(frame);
        if (parsed) {
          onEvent(parsed.payload);
        }
        separator = buffer.indexOf(FRAME_SEPARATOR);
      }
    }
  } finally {
    reader.cancel().catch(() => {});
  }
}
