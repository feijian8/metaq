/**
 * $Id: MetaQueueLogistic.java 3 2013-01-05 08:20:46Z shijia $
 */
package com.taobao.metaq.store;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.log4j.Logger;


/**
 * 逻辑队列都是定长，且必须是16的整数倍<br>
 * 逻辑队列由后台线程串行刷盘<br>
 * 存储单元=Offset(8Byte)+Size(4Byte)+MessageType(4Byte)
 *
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 */
public class MetaQueueLogistic {
    private static final Logger log = Logger.getLogger(MetaStore.MetaStoreLogName);
    // 存储单元大小
    public static final int StoreUnitSize = 16;
    // 存储顶层对象
    private final DefaultMetaStore defaultMetaStore;
    // 存储消息索引的队列
    private final MapedFileQueue mapedFileQueue;
    // Topic
    private final String topic;
    // queueId
    private final int queueId;
    // 最后一个消息对应的物理Offset
    private long maxPhysicOffset = -1;
    // 逻辑队列的最小Offset，删除物理文件时，计算出来的最小Offset
    // 实际使用需要除以 StoreUnitSize
    private volatile long minLogicOffset = 0;
    // 写索引时用到的ByteBuffer
    private final ByteBuffer byteBufferIndex;


    public MetaQueueLogistic(DefaultMetaStore defaultMetaStore, String topic, int queueId) {
        this.defaultMetaStore = defaultMetaStore;
        this.topic = topic;
        this.queueId = queueId;

        String queueDir = defaultMetaStore.getMetaStoreConfig().getStorePathLogics()//
                + File.separator + topic//
                + File.separator + queueId;//

        this.mapedFileQueue =
                new MapedFileQueue(queueDir, defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics(),
                    defaultMetaStore.getAllocateMapedFileService());

        this.byteBufferIndex = ByteBuffer.allocate(StoreUnitSize);
    }


    public boolean load() {
        boolean result = this.mapedFileQueue.load();
        log.info("load logics queue " + this.topic + "-" + this.queueId + " " + (result ? "OK" : "Failed"));
        return result;
    }


    public void recover() {
        final List<MapedFile> mapedFiles = this.mapedFileQueue.getMapedFiles();
        if (!mapedFiles.isEmpty()) {
            // 从倒数第三个文件开始恢复
            int index = mapedFiles.size() - 3;
            if (index < 0)
                index = 0;

            int mapedFileSizeLogics = this.defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics();
            MapedFile mapedFile = mapedFiles.get(index);
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
            long processOffset = mapedFile.getFileFromOffset();
            long mapedFileOffset = 0;
            while (true) {
                for (int i = 0; i < mapedFileSizeLogics; i += StoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    int type = byteBuffer.getInt();

                    // 说明当前存储单元有效
                    // TODO 这样判断有效是否合理？
                    if (offset >= 0 && size > 0) {
                        mapedFileOffset = i + StoreUnitSize;
                        this.maxPhysicOffset = offset;
                    }
                    else {
                        log.info("recover current logics file over,  " + mapedFile.getFileName() + " " + offset
                                + " " + size + " " + type);
                        break;
                    }
                }

                // 走到文件末尾，切换至下一个文件
                if (mapedFileOffset == mapedFileSizeLogics) {
                    index++;
                    if (index >= mapedFiles.size()) {
                        // 当前条件分支不可能发生
                        log.info("recover last logics file over, last maped file " + mapedFile.getFileName());
                        break;
                    }
                    else {
                        mapedFile = mapedFiles.get(index);
                        byteBuffer = mapedFile.sliceByteBuffer();
                        processOffset = mapedFile.getFileFromOffset();
                        mapedFileOffset = 0;
                        log.info("recover next logics file, " + mapedFile.getFileName());
                    }
                }
                else {
                    log.info("recover current logics queue over " + mapedFile.getFileName() + " "
                            + (processOffset + mapedFileOffset));
                    break;
                }
            }

            processOffset += mapedFileOffset;
            this.mapedFileQueue.truncateDirtyFiles(processOffset);
        }
    }


    public long getMaxOffsetInQuque() {
        return this.mapedFileQueue.getMaxOffset() / StoreUnitSize;
    }


    public long getMinOffsetInQuque() {
        return this.minLogicOffset / StoreUnitSize;
    }


    /**
     * 二分查找查找消息发送时间最接近timestamp逻辑队列的offset
     */
    public long getOffsetInQueueByTime(final long timestamp) {
        MapedFile mapedFile = this.mapedFileQueue.getMapedFileByTime(timestamp);
        if (mapedFile != null) {
            long offset = 0;
            // low:第一个索引信息的起始位置
            // minLogicOffset有设置值则从
            // minLogicOffset-mapedFile.getFileFromOffset()位置开始才是有效值
            int low = minLogicOffset>mapedFile.getFileFromOffset() ? 
            		(int)(minLogicOffset-mapedFile.getFileFromOffset()) : 0 ;
            		
            // high:最后一个索引信息的起始位置
            int high = 0;
            int midOffset = -1, targetOffset = -1, leftOffset = -1, rightOffset = -1;
            long leftIndexValue = -1L, rightIndexValue = -1L;

            // 取出该mapedFile里面所有的映射空间(没有映射的空间并不会返回,不会返回文件空洞)
            SelectMapedBufferResult sbr = mapedFile.selectMapedBuffer(0);
            if (null != sbr) {
                ByteBuffer byteBuffer = sbr.getByteBuffer();
                high = byteBuffer.limit() - StoreUnitSize;
                try {
                    while (high >= low) {
                        midOffset = (low + high) / (2 * StoreUnitSize) * StoreUnitSize;
                        byteBuffer.position(midOffset);
                        long phyOffset = byteBuffer.getLong();
                        int size = byteBuffer.getInt();

                        // 比较时间, 折半
                        long storeTime =
                                this.defaultMetaStore.getMetaQueuePhysical().pickupStoretimestamp(phyOffset, size);
                        if (storeTime < 0) {
                            // 没有从物理文件找到消息，此时直接返回0
                            return 0;
                        }
                        else if (storeTime == timestamp) {
                            targetOffset = midOffset;
                            break;
                        }
                        else if (storeTime > timestamp) {
                            high = midOffset - StoreUnitSize;
                            rightOffset = midOffset;
                            rightIndexValue = storeTime;
                        }
                        else {
                            low = midOffset + StoreUnitSize;
                            leftOffset = midOffset;
                            leftIndexValue = storeTime;
                        }
                    }

                    if (targetOffset != -1) {
                        // 查询的时间正好是消息索引记录写入的时间
                        offset = targetOffset;
                    }
                    else {
                        if (leftIndexValue == -1) {
                            // timestamp 时间小于该MapedFile中第一条记录记录的时间
                            offset = rightOffset;
                        }
                        else if (rightIndexValue == -1) {
                            // timestamp 时间大于该MapedFile中最后一条记录记录的时间
                            offset = leftOffset;
                        }
                        else {
                            // 取最接近timestamp的offset
                            offset =
                                    Math.abs(timestamp - leftIndexValue) > Math.abs(timestamp - rightIndexValue) ? rightOffset
                                            : leftOffset;
                        }
                    }

                    return (mapedFile.getFileFromOffset() + offset) / StoreUnitSize;
                }
                finally {
                    sbr.release();
                }
            }
        }

        // 映射文件被标记为不可用时返回0
        return 0;
    }


    /**
     * 根据物理Offset删除无效逻辑文件
     */
    public void truncateDirtyLogicFiles(long phyOffet) {
        // 逻辑队列每个文件大小
        int logicFileSize = this.defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics();

        // 先改变逻辑队列存储的物理Offset
        this.maxPhysicOffset = phyOffet - 1;

        while (true) {
            MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
            if (mapedFile != null) {
                ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
                // 先将Offset清空
                mapedFile.setWrotePostion(0);
                mapedFile.setCommittedPosition(0);

                for (int i = 0; i < logicFileSize; i += StoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    byteBuffer.getInt();

                    // 逻辑文件起始单元
                    if (0 == i) {
                        if (offset >= phyOffet) {
                            this.mapedFileQueue.deleteLastMapedFile();
                            break;
                        }
                        else {
                            int pos = i + StoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;
                        }
                    }
                    // 逻辑文件中间单元
                    else {
                        // 说明当前存储单元有效
                        if (offset >= 0 && size > 0) {
                            // 如果逻辑队列存储的最大物理offset大于物理队列最大offset，则返回
                            if (offset >= phyOffet) {
                                return;
                            }

                            int pos = i + StoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;

                            // 如果最后一个MapedFile扫描完，则返回
                            if (pos == logicFileSize) {
                                return;
                            }
                        }
                        else {
                            return;
                        }
                    }
                }
            }
            else {
                break;
            }
        }
    }


    /**
     * 返回最后一条消息对应物理队列的Next Offset
     */
    public long getLastOffset() {
        // 物理队列Offset
        long lastOffset = -1;
        // 逻辑队列每个文件大小
        int logicFileSize = this.defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics();

        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
        if (mapedFile != null) {
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();

            // 先将Offset清空
            mapedFile.setWrotePostion(0);
            mapedFile.setCommittedPosition(0);

            for (int i = 0; i < logicFileSize; i += StoreUnitSize) {
                long offset = byteBuffer.getLong();
                int size = byteBuffer.getInt();
                byteBuffer.getInt();

                // 说明当前存储单元有效
                if (offset >= 0 && size > 0) {
                    lastOffset = offset + size;
                    int pos = i + StoreUnitSize;
                    mapedFile.setWrotePostion(pos);
                    mapedFile.setCommittedPosition(pos);
                    this.maxPhysicOffset = offset;
                }
                else {
                    break;
                }
            }
        }

        return lastOffset;
    }


    public boolean commit(final int flushLeastPages) {
        return this.mapedFileQueue.commit(flushLeastPages);
    }


    public int deleteExpiredFile(long offset) {
        int cnt = this.mapedFileQueue.deleteExpiredFileByOffset(offset);
        // 无论是否删除文件，都需要纠正下最小值，因为有可能物理文件删除了，
        // 但是逻辑文件一个也删除不了
        this.correctMinOffset(offset);
        return cnt;
    }


    /**
     * 逻辑队列的最小Offset要比传入的物理最小phyMinOffset大
     */
    public void correctMinOffset(long phyMinOffset) {
        MapedFile mapedFile = this.mapedFileQueue.getFirstMapedFileOnLock();
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer(0);
            if (result != null) {
                try {
                    // 有消息存在
                    for (int i = 0; i < result.getSize(); i += MetaQueueLogistic.StoreUnitSize) {
                        long offsetPy = result.getByteBuffer().getLong();
                        result.getByteBuffer().getInt();
                        result.getByteBuffer().getInt();

                        if (offsetPy >= phyMinOffset) {
                            this.minLogicOffset = result.getMapedFile().getFileFromOffset() + i;
                            log.info("compute logics min offset: " + this.getMinOffsetInQuque() + ", topic: "
                                    + this.topic + ", queueId: " + this.queueId);
                            break;
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    result.release();
                }
            }
        }
    }


    /**
     * 存储一个16字节的信息，putIndex只有一个线程调用，所以不需要加锁
     * 
     * @param offset
     *            消息对应的物理分区offset
     * @param size
     *            消息在物理分区存储的大小
     * @param msgType
     *            消息类型
     * @return 是否成功
     */
    public boolean putIndex(final long offset, final int size, final int msgType, final long logicOffset) {
        // 在数据恢复时会走到这个流程
        if (offset <= this.maxPhysicOffset) {
            return true;
        }

        this.byteBufferIndex.flip();
        this.byteBufferIndex.limit(StoreUnitSize);
        this.byteBufferIndex.putLong(offset);
        this.byteBufferIndex.putInt(size);
        this.byteBufferIndex.putInt(msgType);

        final long realLogicOffset = logicOffset * StoreUnitSize;

        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile(realLogicOffset);
        if (mapedFile != null) {
            // 纠正MapedFile逻辑队列索引顺序
            if (mapedFile.isFirstCreateInQueue() && logicOffset != 0 && mapedFile.getWrotePostion() == 0) {
                this.minLogicOffset = realLogicOffset;
                this.fillPreBlank(mapedFile, realLogicOffset);
                log.info("fill pre blank space " + mapedFile.getFileName() + " " + realLogicOffset + " "
                        + mapedFile.getWrotePostion());
            }

            if (realLogicOffset != (mapedFile.getWrotePostion() + mapedFile.getFileFromOffset())) {
                log.warn("logic queue order maybe wrong " + realLogicOffset + " "
                        + (mapedFile.getWrotePostion() + mapedFile.getFileFromOffset()));
            }

            // 记录物理队列最大offset
            this.maxPhysicOffset = offset;
            return mapedFile.appendMessage(this.byteBufferIndex.array());
        }

        return false;
    }


    private void fillPreBlank(final MapedFile mapedFile, final long untilWhere) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(StoreUnitSize);
        byteBuffer.putLong(0);
        byteBuffer.putInt(Integer.MAX_VALUE);
        byteBuffer.putInt(0);

        int until = (int) (untilWhere % this.mapedFileQueue.getMapedFileSize());
        for (int i = 0; i < until; i += StoreUnitSize) {
            mapedFile.appendMessage(byteBuffer.array());
        }
    }


    /**
     * 返回Index Buffer
     * 
     * @param startIndex
     *            起始偏移量索引
     */
    public SelectMapedBufferResult getIndexBuffer(final long startIndex) {
        int mapedFileSize = this.defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics();
        long offset = startIndex * StoreUnitSize;
        MapedFile mapedFile = this.mapedFileQueue.findMapedFileByOffset(offset);
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer((int) (offset % mapedFileSize));
            return result;
        }

        return null;
    }


    public long rollNextFile(final long index) {
        int mapedFileSize = this.defaultMetaStore.getMetaStoreConfig().getMapedFileSizeLogics();
        int totalUnitsInFile = mapedFileSize / StoreUnitSize;
        return (index + totalUnitsInFile - index % totalUnitsInFile);
    }


    public String getTopic() {
        return topic;
    }


    public int getQueueId() {
        return queueId;
    }


    public long getMaxPhysicOffset() {
        return maxPhysicOffset;
    }


    public void setMaxPhysicOffset(long maxPhysicOffset) {
        this.maxPhysicOffset = maxPhysicOffset;
    }


    public void destroy() {
        this.maxPhysicOffset = -1;
        this.minLogicOffset = 0;
        this.mapedFileQueue.destroy();
    }


    public long getMinLogicOffset() {
        return minLogicOffset;
    }


    public void setMinLogicOffset(long minLogicOffset) {
        this.minLogicOffset = minLogicOffset;
    }


    /**
     * 获取当前队列中的消息总数
     */
    public long getMessageTotalInQueue() {
        return this.getMaxOffsetInQuque() - this.getMinOffsetInQuque();
    }
}
