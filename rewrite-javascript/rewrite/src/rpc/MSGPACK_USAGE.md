# MsgPack Encoding for VS.Code JSON-RPC

This module provides MsgPack encoding/decoding support for the VS.Code JSON-RPC library, offering a more efficient binary serialization format compared to JSON.

## Overview

The MsgPack encoding implementation includes:
- **MsgPackContentTypeEncoder**: Encodes JSON-RPC messages to MsgPack format
- **MsgPackContentTypeDecoder**: Decodes MsgPack data to JSON-RPC messages
- **MsgPackMessageReader**: Reads JSON-RPC messages from a stream using MsgPack encoding
- **MsgPackMessageWriter**: Writes JSON-RPC messages to a stream using MsgPack encoding

## Benefits of MsgPack

- **Smaller payload size**: MsgPack produces smaller binary payloads compared to JSON
- **Faster serialization**: Binary encoding is faster than text-based JSON
- **Type preservation**: Better handling of binary data and numeric types
- **Compatible with JSON-RPC**: Drop-in replacement for standard JSON encoding

## Installation

The `@msgpack/msgpack` package is already included as a dependency in package.json:

```json
"dependencies": {
  "@msgpack/msgpack": "^3.0.0"
}
```

## Usage

### Basic Encoder/Decoder Usage

```typescript
import { MsgPackContentTypeEncoder, MsgPackContentTypeDecoder } from '@openrewrite/rewrite/rpc';

// Create encoder and decoder
const encoder = new MsgPackContentTypeEncoder();
const decoder = new MsgPackContentTypeDecoder();

// Encode a message
const message = {
    jsonrpc: "2.0",
    id: 1,
    method: "test/method",
    params: { foo: "bar" }
};
const encoded = encoder.encode(message);

// Decode the message
const decoded = decoder.decode(encoded);
```

### Using with Message Streams

```typescript
import { MsgPackMessageReader, MsgPackMessageWriter } from '@openrewrite/rewrite/rpc';
import * as rpc from 'vscode-jsonrpc';

// Create reader and writer for stdin/stdout
const reader = new MsgPackMessageReader(process.stdin);
const writer = new MsgPackMessageWriter(process.stdout);

// Create message connection
const connection = rpc.createMessageConnection(reader, writer);

// Start listening
connection.listen();
```

### Complete Server Example

Here's how to modify the existing server to use MsgPack encoding:

```typescript
import * as rpc from "vscode-jsonrpc/node";
import { MsgPackMessageReader, MsgPackMessageWriter } from "./msgpack-encoding";
import { RewriteRpc } from "./rewrite-rpc";

// Create the connection with MsgPack encoding
const connection = rpc.createMessageConnection(
    new MsgPackMessageReader(process.stdin),
    new MsgPackMessageWriter(process.stdout),
    logger
);

// Use the connection as normal
new RewriteRpc(connection, options);
```

### Handling Binary Data

MsgPack is particularly useful when dealing with binary data:

```typescript
import { MsgPackContentTypeEncoder, MsgPackContentTypeDecoder } from '@openrewrite/rewrite/rpc';

const encoder = new MsgPackContentTypeEncoder();
const decoder = new MsgPackContentTypeDecoder();

// Message with binary data
const message = {
    jsonrpc: "2.0",
    id: 1,
    method: "process/binary",
    params: {
        data: Buffer.from([1, 2, 3, 4, 5]),
        filename: "example.bin"
    }
};

// Encode and decode
const encoded = encoder.encode(message);
const decoded = decoder.decode(encoded);

// Binary data is preserved as Uint8Array
const binaryData = Buffer.from(decoded.params.data);
```

## Message Format

Messages are formatted with headers similar to the JSON-RPC protocol:

```
Content-Length: <length>\r\n
Content-Type: application/msgpack\r\n
\r\n
<msgpack-encoded-message>
```

## API Reference

### MsgPackContentTypeEncoder

```typescript
class MsgPackContentTypeEncoder {
    readonly name: string; // "application/msgpack"
    encode(msg: Message): Buffer;
}
```

### MsgPackContentTypeDecoder

```typescript
class MsgPackContentTypeDecoder {
    readonly name: string; // "application/msgpack"
    decode(buffer: Buffer): Message;
}
```

### MsgPackMessageReader

```typescript
class MsgPackMessageReader implements MessageReader {
    constructor(readable: Readable);

    get onError(): Event<Error>;
    get onClose(): Event<void>;
    get onPartialMessage(): Event<PartialMessageInfo>;

    listen(callback: DataCallback): Disposable;
    dispose(): void;
}
```

### MsgPackMessageWriter

```typescript
class MsgPackMessageWriter implements MessageWriter {
    constructor(writable: Writable);

    get onError(): Event<[Error, Message | undefined, number | undefined]>;
    get onClose(): Event<void>;

    write(msg: Message): Promise<void>;
    end(): void;
    dispose(): void;
}
```

## Testing

The implementation includes comprehensive tests covering:
- Basic encoding/decoding of all message types (requests, responses, notifications, errors)
- Complex nested data structures
- Binary data handling
- Streaming with chunked data
- Error handling
- Round-trip testing

Run the tests with:

```bash
npm test -- test/rpc/msgpack-encoding.test.ts
```

## Performance Considerations

MsgPack encoding typically provides:
- **20-30% smaller payload size** compared to JSON
- **Faster serialization/deserialization** due to binary format
- **Better handling of large binary data** without base64 encoding

## Compatibility

- Compatible with VS.Code JSON-RPC v8.2.1+
- Works with all standard JSON-RPC message types
- Fully compatible with the existing RewriteRpc implementation
- Can be used as a drop-in replacement for StreamMessageReader/StreamMessageWriter

## Migration from JSON to MsgPack

To migrate an existing JSON-RPC server to MsgPack:

1. Replace `StreamMessageReader` with `MsgPackMessageReader`:
   ```typescript
   // Before
   new rpc.StreamMessageReader(process.stdin)

   // After
   new MsgPackMessageReader(process.stdin)
   ```

2. Replace `StreamMessageWriter` with `MsgPackMessageWriter`:
   ```typescript
   // Before
   new rpc.StreamMessageWriter(process.stdout)

   // After
   new MsgPackMessageWriter(process.stdout)
   ```

3. Ensure both client and server use MsgPack encoding for proper communication.

## Troubleshooting

### "Missing Content-Length header" error
- Ensure messages include proper headers
- Check that both ends of the connection use MsgPack encoding

### Type conversion issues
- MsgPack may preserve binary data as Uint8Array instead of Buffer
- Use `Buffer.from()` to convert when needed

### Connection errors
- Verify both client and server are using compatible encoding
- Check that streams are properly connected
- Enable logging to debug message flow

## See Also

- [MsgPack Specification](https://msgpack.org/)
- [VS.Code JSON-RPC Documentation](https://www.npmjs.com/package/vscode-jsonrpc)
- [@msgpack/msgpack Package](https://www.npmjs.com/package/@msgpack/msgpack)
