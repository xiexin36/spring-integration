/*
 * Copyright 2002-2007 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.message;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.integration.SystemInterruptedException;
import org.springframework.integration.util.RandomGuidUidGenerator;
import org.springframework.integration.util.UidGenerator;
import org.springframework.util.Assert;

/**
 * Base Message class defining common properties such as id, header, and lock.
 * 
 * @author Mark Fisher
 */
public class GenericMessage<T> implements Message<T> {

	private Object id;

	private MessageHeader header = new MessageHeader();

	private T payload;

	private UidGenerator defaultUidGenerator = new RandomGuidUidGenerator();

	private ReentrantLock lock;


	/**
	 * Create a new message with the given id and payload.
	 * 
	 * @param id unique identifier for this message
	 * @param payload the message payload
	 */
	public GenericMessage(Object id, T payload) {
		Assert.notNull(id, "id must not be null");
		Assert.notNull(payload, "payload must not be null");
		this.id = id;
		this.payload = payload;
	}

	/**
	 * Create a new message with the given payload. The id will be generated by
	 * the default {@link UidGenerator} strategy.
	 * 
	 * @param payload the message payload
	 */
	public GenericMessage(T payload) {
		Assert.notNull(payload, "payload must not be null");
		this.id = this.defaultUidGenerator.generateUid();
		this.payload = payload;
	}


	public Object getId() {
		return this.id;
	}

	public MessageHeader getHeader() {
		return this.header;
	}

	protected void setHeader(MessageHeader header) {
		this.header = header;
	}

	public T getPayload() {
		return this.payload;
	}

	protected void setPayload(T newPayload) {
		this.payload = newPayload;
	}

	public void lock() {
		this.lock.lock();
	}

	public void lockInterruptibly() {
		try {
			lock.lockInterruptibly();
		}
		catch (InterruptedException e) {
			throw new SystemInterruptedException("Unable to obtain lock for message", e);
		}
	}

	public void unlock() {
		lock.unlock();
	}

}
