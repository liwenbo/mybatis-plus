/**
 * Copyright (c) 2011-2020, hubin (jobob@qq.com).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.baomidou.mybatisplus.toolkit;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;

import com.baomidou.mybatisplus.exceptions.MybatisPlusException;

/**
 * <p>
 * 分布式高效有序ID生产黑科技(sequence)
 * </p>
 * 
 * @author hubin
 * @date 2016-08-01
 */
public class Sequence {
	/* 时间起始标记点，作为基准，一般取系统的最近时间（一旦确定不能变动）*/
	private final long twepoch = 1288834974657L;
	private final long workerIdBits = 10L;/* 机器标识位数 */
	private final long datacenterIdBits = 10L;
	private final long maxWorkerId = -1L ^ (-1L << workerIdBits);/* 机器ID最大值 1023 */
	private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);
	private final long sequenceBits = 12L;/* 毫秒内自增位 */
	private final long workerIdShift = sequenceBits;
	private final long datacenterIdShift = sequenceBits + workerIdBits;
	/* 时间戳左移动位 */
	private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
	private final long sequenceMask = -1L ^ (-1L << sequenceBits);

	private long workerId;
	
	/* 数据标识id部分 */
	private long datacenterId;
	private long sequence = 0L;/* 0，并发控制 */
	private long lastTimestamp = -1L;/* 上次生产id时间戳 */
	
	public Sequence() {
		this.datacenterId = getDatacenterId();
		/* MAC + PID 的 hashcode 获取16个低位 */
		long macPidHashCode = (datacenterId + "" + getJvmPid()).hashCode() & 0xffff;
		this.workerId = macPidHashCode % (maxWorkerId + 1);
	}

	/**
	 * @param workerId
	 *            工作机器ID
	 * @param datacenterId
	 *            序列号
	 */
	public Sequence(long workerId, long datacenterId) {
		if (workerId > maxWorkerId || workerId < 0) {
			throw new IllegalArgumentException(
					String.format("worker Id can't be greater than %d or less than 0", maxWorkerId));
		}
		if (datacenterId > maxDatacenterId || datacenterId < 0) {
			throw new IllegalArgumentException(
					String.format("datacenter Id can't be greater than %d or less than 0", maxDatacenterId));
		}
		this.workerId = workerId;
		this.datacenterId = datacenterId;
	}

	/**
	 * 获取下一个ID
	 * 
	 * @return
	 */
	public synchronized long nextId() {
		long timestamp = timeGen();
		if (timestamp < lastTimestamp) {
			throw new RuntimeException(String.format(
					"Clock moved backwards. Refusing to generate id for %d milliseconds", lastTimestamp - timestamp));
		}
		if (lastTimestamp == timestamp) {
			sequence = (sequence + 1) & sequenceMask;
			if (sequence == 0) {
				timestamp = tilNextMillis(lastTimestamp);
			}
		} else {
			sequence = 0L;
		}

		lastTimestamp = timestamp;

		return ((timestamp - twepoch) << timestampLeftShift) | (datacenterId << datacenterIdShift)
				| (workerId << workerIdShift) | sequence;
	}

	protected long tilNextMillis(long lastTimestamp) {
		long timestamp = timeGen();
		while (timestamp <= lastTimestamp) {
			timestamp = timeGen();
		}
		return timestamp;
	}

	protected long timeGen() {
		return SystemClock.now();
	}
	
	/**
	 * <p>
	 * 获取 PID
	 * </p>
	 */
	private static String getJvmPid() {
		String name = ManagementFactory.getRuntimeMXBean().getName();
		// get pid
		if (name != null) {
			return name.split("@")[0];
		}
		return null;
	}

	/**
	 * <p>
	 * 数据标识id部分
	 * </p>
	 */
	protected static long getDatacenterId() {
		try {
			InetAddress ip = InetAddress.getLocalHost();
			NetworkInterface network = NetworkInterface.getByInetAddress(ip);
			long id;
			if (network == null) {
				id = 1;
			} else {
				byte[] mac = network.getHardwareAddress();
				id = ((0x000000FF & (long) mac[mac.length - 1])
						| (0x0000FF00 & (((long) mac[mac.length - 2]) << 8))) >> 6;
			}
			return id;
		} catch (Exception e) {
			throw new MybatisPlusException(e);
		}
	}

}
