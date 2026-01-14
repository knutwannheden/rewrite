/*
 * Copyright 2025 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import * as msgpack from '@msgpack/msgpack';
import {
    DataCallback,
    Disposable,
    Message,
    MessageReader,
    MessageWriter,
    PartialMessageInfo
} from 'vscode-jsonrpc';
import { Emitter, Event } from 'vscode-jsonrpc';
import { Readable, Writable } from 'stream';

const MSGPACK_CONTENT_TYPE = 'application/msgpack';

/**
 * ContentTypeEncoder for MsgPack encoding.
 * Implements the encoding of JSON-RPC messages to MsgPack format.
 */
export class MsgPackContentTypeEncoder {
    readonly name: string = MSGPACK_CONTENT_TYPE;

    /**
     * Encodes a JSON-RPC message to MsgPack format.
     * @param msg The message to encode
     * @returns Encoded message as a Buffer
     */
    encode(msg: Message): Buffer {
        return Buffer.from(msgpack.encode(msg));
    }
}

/**
 * ContentTypeDecoder for MsgPack decoding.
 * Implements the decoding of MsgPack data to JSON-RPC messages.
 */
export class MsgPackContentTypeDecoder {
    readonly name: string = MSGPACK_CONTENT_TYPE;

    /**
     * Decodes a MsgPack buffer to a JSON-RPC message.
     * @param buffer The buffer to decode
     * @returns Decoded message
     */
    decode(buffer: Buffer): Message {
        return msgpack.decode(buffer) as Message;
    }
}

/**
 * MessageReader that reads JSON-RPC messages from a stream using MsgPack encoding.
 * This reader expects messages in the format:
 * Content-Length: <length>\r\n
 * Content-Type: application/msgpack\r\n
 * \r\n
 * <msgpack-encoded-message>
 */
export class MsgPackMessageReader implements MessageReader {
    private readable: Readable;
    private decoder: MsgPackContentTypeDecoder;
    private buffer: Buffer;
    private nextMessageLength: number;

    private readonly _onError: Emitter<Error>;
    private readonly _onClose: Emitter<void>;
    private readonly _onPartialMessage: Emitter<PartialMessageInfo>;

    constructor(readable: Readable) {
        this.readable = readable;
        this.decoder = new MsgPackContentTypeDecoder();
        this.buffer = Buffer.alloc(0);
        this.nextMessageLength = -1;

        this._onError = new Emitter<Error>();
        this._onClose = new Emitter<void>();
        this._onPartialMessage = new Emitter<PartialMessageInfo>();
    }

    get onError(): Event<Error> {
        return this._onError.event;
    }

    get onClose(): Event<void> {
        return this._onClose.event;
    }

    get onPartialMessage(): Event<PartialMessageInfo> {
        return this._onPartialMessage.event;
    }

    listen(callback: DataCallback): Disposable {
        this.readable.on('data', (chunk: Buffer) => {
            this.handleData(chunk, callback);
        });

        this.readable.on('error', (error: Error) => {
            this._onError.fire(error);
        });

        this.readable.on('close', () => {
            this._onClose.fire();
        });

        return {
            dispose: () => {
                this.dispose();
            }
        };
    }

    private handleData(chunk: Buffer, callback: DataCallback): void {
        this.buffer = Buffer.concat([this.buffer, chunk]);

        while (true) {
            if (this.nextMessageLength === -1) {
                // Try to read the header
                const headerEnd = this.buffer.indexOf('\r\n\r\n');
                if (headerEnd === -1) {
                    // Not enough data for a complete header
                    break;
                }

                const header = this.buffer.subarray(0, headerEnd).toString('utf-8');
                const contentLengthMatch = header.match(/Content-Length:\s*(\d+)/i);

                if (!contentLengthMatch) {
                    this._onError.fire(new Error('Missing Content-Length header'));
                    break;
                }

                this.nextMessageLength = parseInt(contentLengthMatch[1], 10);
                this.buffer = this.buffer.subarray(headerEnd + 4); // Skip \r\n\r\n
            }

            if (this.buffer.length >= this.nextMessageLength) {
                // We have enough data for the complete message
                const messageBuffer = this.buffer.subarray(0, this.nextMessageLength);
                this.buffer = this.buffer.subarray(this.nextMessageLength);
                this.nextMessageLength = -1;

                try {
                    const message = this.decoder.decode(messageBuffer);
                    callback(message);
                } catch (error) {
                    this._onError.fire(error instanceof Error ? error : new Error(String(error)));
                }
            } else {
                // Not enough data for the complete message
                this._onPartialMessage.fire({
                    messageToken: 0,
                    waitingTime: 0
                });
                break;
            }
        }
    }

    dispose(): void {
        this.readable.destroy();
        this._onError.dispose();
        this._onClose.dispose();
        this._onPartialMessage.dispose();
    }
}

/**
 * MessageWriter that writes JSON-RPC messages to a stream using MsgPack encoding.
 * This writer formats messages as:
 * Content-Length: <length>\r\n
 * Content-Type: application/msgpack\r\n
 * \r\n
 * <msgpack-encoded-message>
 */
export class MsgPackMessageWriter implements MessageWriter {
    private writable: Writable;
    private encoder: MsgPackContentTypeEncoder;

    private readonly _onError: Emitter<[Error, Message | undefined, number | undefined]>;
    private readonly _onClose: Emitter<void>;

    constructor(writable: Writable) {
        this.writable = writable;
        this.encoder = new MsgPackContentTypeEncoder();

        this._onError = new Emitter<[Error, Message | undefined, number | undefined]>();
        this._onClose = new Emitter<void>();

        writable.on('error', (error: Error) => {
            this._onError.fire([error, undefined, undefined]);
        });

        writable.on('close', () => {
            this._onClose.fire();
        });
    }

    get onError(): Event<[Error, Message | undefined, number | undefined]> {
        return this._onError.event;
    }

    get onClose(): Event<void> {
        return this._onClose.event;
    }

    async write(msg: Message): Promise<void> {
        try {
            const encoded = this.encoder.encode(msg);
            const headers = `Content-Length: ${encoded.length}\r\nContent-Type: ${MSGPACK_CONTENT_TYPE}\r\n\r\n`;
            const headerBuffer = Buffer.from(headers, 'utf-8');
            const fullMessage = Buffer.concat([headerBuffer, encoded]);

            return new Promise((resolve, reject) => {
                this.writable.write(fullMessage, (error) => {
                    if (error) {
                        this._onError.fire([error, msg, fullMessage.length]);
                        reject(error);
                    } else {
                        resolve();
                    }
                });
            });
        } catch (error) {
            const err = error instanceof Error ? error : new Error(String(error));
            this._onError.fire([err, msg, undefined]);
            throw err;
        }
    }

    end(): void {
        this.writable.end();
    }

    dispose(): void {
        this.writable.destroy();
        this._onError.dispose();
        this._onClose.dispose();
    }
}
