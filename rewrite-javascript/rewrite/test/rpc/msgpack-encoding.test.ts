import {
    MsgPackContentTypeEncoder,
    MsgPackContentTypeDecoder,
    MsgPackMessageReader,
    MsgPackMessageWriter
} from "../../src/rpc/msgpack-encoding";
import { Readable, Writable } from "stream";
import { Message, RequestMessage, ResponseMessage, NotificationMessage } from "vscode-jsonrpc";

describe("MsgPack Content Type Encoder/Decoder", () => {
    let encoder: MsgPackContentTypeEncoder;
    let decoder: MsgPackContentTypeDecoder;

    beforeEach(() => {
        encoder = new MsgPackContentTypeEncoder();
        decoder = new MsgPackContentTypeDecoder();
    });

    test("encoder has correct content type name", () => {
        expect(encoder.name).toBe("application/msgpack");
    });

    test("decoder has correct content type name", () => {
        expect(decoder.name).toBe("application/msgpack");
    });

    test("encode and decode a simple request message", () => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "test/method",
            params: { foo: "bar", num: 42 }
        };

        const encoded = encoder.encode(message);
        expect(Buffer.isBuffer(encoded)).toBeTruthy();
        expect(encoded.length).toBeGreaterThan(0);

        const decoded = decoder.decode(encoded);
        expect(decoded).toEqual(message);
    });

    test("encode and decode a response message", () => {
        const message: ResponseMessage = {
            jsonrpc: "2.0",
            id: 1,
            result: { status: "success", data: [1, 2, 3] }
        };

        const encoded = encoder.encode(message);
        const decoded = decoder.decode(encoded);
        expect(decoded).toEqual(message);
    });

    test("encode and decode an error response message", () => {
        const message: ResponseMessage = {
            jsonrpc: "2.0",
            id: 1,
            error: {
                code: -32600,
                message: "Invalid Request",
                data: { details: "Missing required parameter" }
            }
        };

        const encoded = encoder.encode(message);
        const decoded = decoder.decode(encoded);
        expect(decoded).toEqual(message);
    });

    test("encode and decode a notification message", () => {
        const message: NotificationMessage = {
            jsonrpc: "2.0",
            method: "test/notification",
            params: { event: "updated" }
        };

        const encoded = encoder.encode(message);
        const decoded = decoder.decode(encoded);
        expect(decoded).toEqual(message);
    });

    test("encode and decode message with complex nested data", () => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 123,
            method: "complex/method",
            params: {
                nested: {
                    array: [1, 2, 3, { foo: "bar" }],
                    object: { a: 1, b: 2, c: { d: 3 } },
                    nullValue: null,
                    boolValue: true,
                    stringValue: "test"
                }
            }
        };

        const encoded = encoder.encode(message);
        const decoded = decoder.decode(encoded);
        expect(decoded).toEqual(message);
    });

    test("encode and decode message with binary data", () => {
        const binaryData = Buffer.from([1, 2, 3, 4, 5]);
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "binary/method",
            params: { data: binaryData }
        };

        const encoded = encoder.encode(message);
        const decoded = decoder.decode(encoded) as RequestMessage;

        // MsgPack preserves binary data as Uint8Array
        expect(decoded.jsonrpc).toBe(message.jsonrpc);
        expect(decoded.id).toBe(message.id);
        expect(decoded.method).toBe(message.method);
        expect(Buffer.from((decoded.params as any).data)).toEqual(binaryData);
    });
});

describe("MsgPack MessageReader", () => {
    test("read a single message from stream", (done) => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "test/method",
            params: { foo: "bar" }
        };

        const encoder = new MsgPackContentTypeEncoder();
        const encoded = encoder.encode(message);
        const headers = `Content-Length: ${encoded.length}\r\nContent-Type: application/msgpack\r\n\r\n`;
        const fullMessage = Buffer.concat([Buffer.from(headers, 'utf-8'), encoded]);

        const readable = new Readable({
            read() {
                this.push(fullMessage);
                this.push(null);
            }
        });

        const reader = new MsgPackMessageReader(readable);
        reader.listen((msg) => {
            expect(msg).toEqual(message);
            reader.dispose();
            done();
        });
    });

    test("read multiple messages from stream", (done) => {
        const messages: RequestMessage[] = [
            { jsonrpc: "2.0", id: 1, method: "method1", params: { a: 1 } },
            { jsonrpc: "2.0", id: 2, method: "method2", params: { b: 2 } },
            { jsonrpc: "2.0", id: 3, method: "method3", params: { c: 3 } }
        ];

        const encoder = new MsgPackContentTypeEncoder();
        const buffers = messages.map(msg => {
            const encoded = encoder.encode(msg);
            const headers = `Content-Length: ${encoded.length}\r\nContent-Type: application/msgpack\r\n\r\n`;
            return Buffer.concat([Buffer.from(headers, 'utf-8'), encoded]);
        });
        const fullBuffer = Buffer.concat(buffers);

        const readable = new Readable({
            read() {
                this.push(fullBuffer);
                this.push(null);
            }
        });

        const reader = new MsgPackMessageReader(readable);
        const received: Message[] = [];

        reader.listen((msg) => {
            received.push(msg);
            if (received.length === messages.length) {
                expect(received).toEqual(messages);
                reader.dispose();
                done();
            }
        });
    });

    test("read message sent in chunks", (done) => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "test/method",
            params: { foo: "bar" }
        };

        const encoder = new MsgPackContentTypeEncoder();
        const encoded = encoder.encode(message);
        const headers = `Content-Length: ${encoded.length}\r\nContent-Type: application/msgpack\r\n\r\n`;
        const fullMessage = Buffer.concat([Buffer.from(headers, 'utf-8'), encoded]);

        // Split into chunks
        const chunkSize = 10;
        const chunks: Buffer[] = [];
        for (let i = 0; i < fullMessage.length; i += chunkSize) {
            chunks.push(fullMessage.subarray(i, Math.min(i + chunkSize, fullMessage.length)));
        }

        let chunkIndex = 0;
        const readable = new Readable({
            read() {
                if (chunkIndex < chunks.length) {
                    this.push(chunks[chunkIndex++]);
                } else {
                    this.push(null);
                }
            }
        });

        const reader = new MsgPackMessageReader(readable);
        reader.listen((msg) => {
            expect(msg).toEqual(message);
            reader.dispose();
            done();
        });
    });

    test("emit error on invalid message", (done) => {
        const readable = new Readable({
            read() {
                // Send invalid data (missing Content-Length header)
                this.push(Buffer.from("Content-Type: application/msgpack\r\n\r\n", 'utf-8'));
                this.push(null);
            }
        });

        const reader = new MsgPackMessageReader(readable);
        reader.onError((error) => {
            expect(error.message).toContain('Content-Length');
            reader.dispose();
            done();
        });

        reader.listen(() => {
            // Should not receive any messages
        });
    });
});

describe("MsgPack MessageWriter", () => {
    test("write a single message to stream", (done) => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "test/method",
            params: { foo: "bar" }
        };

        const chunks: Buffer[] = [];
        const writable = new Writable({
            write(chunk, encoding, callback) {
                chunks.push(chunk);
                callback();
            }
        });

        const writer = new MsgPackMessageWriter(writable);
        writer.write(message).then(() => {
            const fullBuffer = Buffer.concat(chunks);
            const content = fullBuffer.toString('utf-8');

            expect(content).toContain('Content-Length:');
            expect(content).toContain('Content-Type: application/msgpack');

            // Extract and verify the message
            const headerEnd = fullBuffer.indexOf('\r\n\r\n');
            expect(headerEnd).toBeGreaterThan(-1);

            const messageBuffer = fullBuffer.subarray(headerEnd + 4);
            const decoder = new MsgPackContentTypeDecoder();
            const decoded = decoder.decode(messageBuffer);
            expect(decoded).toEqual(message);

            writer.dispose();
            done();
        });
    });

    test("write multiple messages to stream", (done) => {
        const messages: RequestMessage[] = [
            { jsonrpc: "2.0", id: 1, method: "method1", params: { a: 1 } },
            { jsonrpc: "2.0", id: 2, method: "method2", params: { b: 2 } }
        ];

        const chunks: Buffer[] = [];
        const writable = new Writable({
            write(chunk, encoding, callback) {
                chunks.push(chunk);
                callback();
            }
        });

        const writer = new MsgPackMessageWriter(writable);
        Promise.all(messages.map(msg => writer.write(msg))).then(() => {
            expect(chunks.length).toBe(messages.length);

            const decoder = new MsgPackContentTypeDecoder();
            chunks.forEach((chunk, index) => {
                const headerEnd = chunk.indexOf('\r\n\r\n');
                const messageBuffer = chunk.subarray(headerEnd + 4);
                const decoded = decoder.decode(messageBuffer);
                expect(decoded).toEqual(messages[index]);
            });

            writer.dispose();
            done();
        });
    });

    test("emit error on write failure", (done) => {
        const message: RequestMessage = {
            jsonrpc: "2.0",
            id: 1,
            method: "test/method",
            params: { foo: "bar" }
        };

        const writable = new Writable({
            write(chunk, encoding, callback) {
                callback(new Error('Write failed'));
            }
        });

        const writer = new MsgPackMessageWriter(writable);
        writer.onError(([error, msg, count]) => {
            expect(error.message).toContain('Write failed');
            expect(msg).toEqual(message);
            writer.dispose();
            done();
        });

        writer.write(message).catch(() => {
            // Expected to fail
        });
    });
});

describe("MsgPack Round-trip (Reader + Writer)", () => {
    test("write and read messages through streams", (done) => {
        const messages: Message[] = [
            { jsonrpc: "2.0", id: 1, method: "method1", params: { a: 1 } } as RequestMessage,
            { jsonrpc: "2.0", id: 2, method: "method2", params: { b: 2 } } as RequestMessage,
            { jsonrpc: "2.0", id: 1, result: { success: true } } as ResponseMessage
        ];

        // Create a pair of connected streams
        let readableController: any;
        const readable = new Readable({
            read() {
                // Data will be pushed from writer
            }
        });

        const writable = new Writable({
            write(chunk, encoding, callback) {
                readable.push(chunk);
                callback();
            }
        });

        const writer = new MsgPackMessageWriter(writable);
        const reader = new MsgPackMessageReader(readable);

        const received: Message[] = [];
        reader.listen((msg) => {
            received.push(msg);
            if (received.length === messages.length) {
                expect(received).toEqual(messages);
                writer.dispose();
                reader.dispose();
                done();
            }
        });

        // Write all messages
        messages.forEach(msg => writer.write(msg));
    });
});
