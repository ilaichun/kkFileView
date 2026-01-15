package cn.keking.service;

import cn.keking.config.ConfigConstants;
import cn.keking.model.FileAttribute;
import cn.keking.utils.FileConvertStatusManager;
import cn.keking.utils.RemoveSvgAdSimple;
import com.aspose.cad.*;
import com.aspose.cad.fileformats.cad.CadDrawTypeMode;
import com.aspose.cad.fileformats.tiff.enums.TiffExpectedFormat;
import com.aspose.cad.imageoptions.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CAD文件转换服务 - 增强版
 * 支持实时状态跟踪和状态锁定机制
 */
@Component
public class CadToPdfService {
    private static final Logger logger = LoggerFactory.getLogger(CadToPdfService.class);

    // 使用虚拟线程执行器
    private final ExecutorService virtualThreadExecutor;
    // 存储正在运行的转换任务
    private final ConcurrentHashMap<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    // 存储任务的完成状态
    private final ConcurrentHashMap<String, AtomicBoolean> taskCompletionStatus = new ConcurrentHashMap<>();
    // 并发控制信号量
    private final Semaphore concurrentLimit;
    // 转换超时时间（秒）
    private final long conversionTimeout;

    public CadToPdfService() {
        int maxConcurrent = getConcurrentLimit();
        this.concurrentLimit = new Semaphore(maxConcurrent);
        this.virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.conversionTimeout = getConversionTimeout();

        logger.info("CAD转换服务初始化完成，最大并发数: {}，转换超时: {}秒", maxConcurrent, conversionTimeout);
    }

    /**
     * CAD文件转换 - 异步版本
     * @param inputFilePath 输入文件路径
     * @param outputFilePath 输出文件路径
     * @param cadPreviewType 预览类型（svg/pdf/tif/tiff）
     * @param fileAttribute 文件属性
     * @return 转换结果的CompletableFuture
     */
    public CompletableFuture<Boolean> cadToPdfAsync(String inputFilePath, String outputFilePath,
                                                    String cadPreviewType, FileAttribute fileAttribute) {
        String fileName = new File(inputFilePath).getName();
        // 立即创建初始状态，防止重复执行
        FileConvertStatusManager.startConvert(fileName);
        // 创建可取消的任务
        CompletableFuture<Boolean> taskFuture = new CompletableFuture<>();
        taskCompletionStatus.put(fileName, new AtomicBoolean(false));

        // 提交任务到线程池
        Future<?> future = virtualThreadExecutor.submit(() -> {
            try {
                // 添加初始状态更新
                FileConvertStatusManager.updateProgress(fileName, "正在启动转换任务", 5);
                boolean result = convertCadWithConcurrencyControl(inputFilePath, outputFilePath,
                        cadPreviewType, fileAttribute);
                if (result) {
                    taskFuture.complete(true);
                    taskCompletionStatus.get(fileName).set(true);
                } else {
                    taskFuture.complete(false);
                }
            } catch (Exception e) {
                logger.error("CAD转换任务执行失败: {}", fileName, e);
                FileConvertStatusManager.markError(fileName, "转换过程异常: " + e.getMessage());
                taskFuture.completeExceptionally(e);
            } finally {
                // 移除任务记录
                runningTasks.remove(fileName);
                taskCompletionStatus.remove(fileName);
            }
        });

        // 记录正在运行的任务
        runningTasks.put(fileName, future);

        // 设置超时取消
        scheduleTimeoutCheck(fileName, taskFuture, future, outputFilePath);

        return taskFuture;
    }

    /**
     * 调度超时检查
     */
    private void scheduleTimeoutCheck(String fileName, CompletableFuture<Boolean> taskFuture,
                                      Future<?> future, String outputFilePath) {
        virtualThreadExecutor.submit(() -> {
            try {
                // 等待任务完成或超时
                taskFuture.get(conversionTimeout, TimeUnit.SECONDS);
                // 正常完成，不需要额外处理
            } catch (TimeoutException e) {
                handleConversionTimeout(fileName, taskFuture, future, outputFilePath);
            } catch (Exception e) {
                handleConversionException(fileName, taskFuture, e);
            }
        });
    }

    /**
     * 处理转换超时
     */
    private void handleConversionTimeout(String fileName, CompletableFuture<Boolean> taskFuture,
                                         Future<?> future, String outputFilePath) {
        logger.error("CAD转换超时，取消任务: {}, 超时时间: {}秒", fileName, conversionTimeout);

        // 标记为超时（最终状态）
        FileConvertStatusManager.markTimeout(fileName);

        // 取消正在运行的任务
        cancelRunningTask(fileName, future);

        // 删除可能已经生成的不完整文件
        deleteIncompleteFile(outputFilePath);

        // 完成Future
        taskFuture.complete(false);
    }

    /**
     * 处理转换异常
     */
    private void handleConversionException(String fileName, CompletableFuture<Boolean> taskFuture,
                                           Exception e) {
        logger.error("CAD转换异常: {}", fileName, e);
        // 标记为失败（最终状态）
        FileConvertStatusManager.markError(fileName, "转换任务异常: " + e.getMessage());
        taskFuture.complete(false);
    }

    /**
     * 取消正在运行的任务
     */
    private void cancelRunningTask(String fileName, Future<?> future) {
        if (future != null) {
            boolean cancelled = future.cancel(true);
            logger.info("尝试取消任务 {}: {}", fileName, cancelled ? "成功" : "失败");
        }
    }

    /**
     * 带并发控制的CAD转换
     */
    private boolean convertCadWithConcurrencyControl(String inputFilePath, String outputFilePath,
                                                     String cadPreviewType, FileAttribute fileAttribute)
            throws Exception {
        String fileName = new File(inputFilePath).getName();
        long acquireStartTime = System.currentTimeMillis();

        // 获取并发许可
        if (!concurrentLimit.tryAcquire(30, TimeUnit.SECONDS)) {
            long acquireTime = System.currentTimeMillis() - acquireStartTime;
            logger.warn("获取并发许可超时，文件: {}, 等待时间: {}ms", fileName, acquireTime);
            FileConvertStatusManager.updateProgress(fileName, "系统繁忙，等待资源中...", 15);
            throw new TimeoutException("系统繁忙，请稍后重试");
        }

        long acquireTime = System.currentTimeMillis() - acquireStartTime;
        logger.debug("获取并发许可成功: {}, 等待时间: {}ms", fileName, acquireTime);

        // 更新状态
        FileConvertStatusManager.updateProgress(fileName, "已获取转换资源，开始转换", 20);

        long conversionStartTime = System.currentTimeMillis();

        try {
            boolean result = performCadConversion(inputFilePath, outputFilePath, cadPreviewType, fileAttribute);

            long conversionTime = System.currentTimeMillis() - conversionStartTime;
            logger.debug("CAD转换核心完成: {}, 转换耗时: {}ms, 总耗时(含等待): {}ms",
                    fileName, conversionTime, conversionTime + acquireTime);

            return result;

        } finally {
            concurrentLimit.release();
        }
    }

    /**
     * 执行实际的CAD转换逻辑
     */
    private boolean performCadConversion(String inputFilePath, String outputFilePath,
                                         String cadPreviewType, FileAttribute fileAttribute) {
        final InterruptionTokenSource source = new InterruptionTokenSource();
        String fileName = new File(inputFilePath).getName();
        long totalStartTime = System.currentTimeMillis();
        try {
            // 1. 验证输入参数
            long validationStartTime = System.currentTimeMillis();
            FileConvertStatusManager.updateProgress(fileName, "正在验证文件参数", 25);
            if (!validateInputParameters(inputFilePath, outputFilePath, cadPreviewType)) {
                long validationTime = System.currentTimeMillis() - validationStartTime;
                logger.error("CAD转换参数验证失败: {}, 验证耗时: {}ms", fileName, validationTime);
                FileConvertStatusManager.markError(fileName, "文件参数验证失败");
                return false;
            }
            long validationTime = System.currentTimeMillis() - validationStartTime;

            // 2. 创建输出目录
            long directoryStartTime = System.currentTimeMillis();
            FileConvertStatusManager.updateProgress(fileName, "正在准备输出目录", 30);
            createOutputDirectoryIfNeeded(outputFilePath, fileAttribute.isCompressFile());
            long directoryTime = System.currentTimeMillis() - directoryStartTime;

            // 3. 加载并转换CAD文件
            long loadStartTime = System.currentTimeMillis();
            FileConvertStatusManager.updateProgress(fileName, "正在加载CAD文件", 40);
            LoadOptions loadOptions = createLoadOptions();

            try (Image cadImage = Image.load(inputFilePath, loadOptions)) {
                long loadTime = System.currentTimeMillis() - loadStartTime;
                logger.debug("CAD文件加载完成: {}, 加载耗时: {}ms", fileName, loadTime);

                FileConvertStatusManager.updateProgress(fileName, "CAD文件加载完成，开始渲染", 50);

                // 4. 创建光栅化选项
                long rasterizationStartTime = System.currentTimeMillis();
                FileConvertStatusManager.updateProgress(fileName, "正在设置渲染参数", 60);
                CadRasterizationOptions rasterizationOptions = createRasterizationOptions(cadImage);
                long rasterizationTime = System.currentTimeMillis() - rasterizationStartTime;

                // 5. 根据预览类型创建选项
                long optionsStartTime = System.currentTimeMillis();
                FileConvertStatusManager.updateProgress(fileName, "正在配置输出格式", 70);
                var options = switch (cadPreviewType.toLowerCase()) {
                    case "svg" -> createSvgOptions(rasterizationOptions, source);
                    case "pdf" -> createPdfOptions(rasterizationOptions, source);
                    case "tif", "tiff" -> createTiffOptions(rasterizationOptions, source);
                    default -> throw new IllegalArgumentException("不支持的预览类型: " + cadPreviewType);
                };
                long optionsTime = System.currentTimeMillis() - optionsStartTime;
                // 6. 保存转换结果
                long saveStartTime = System.currentTimeMillis();
                FileConvertStatusManager.updateProgress(fileName, "正在生成输出文件", 80);
                saveConvertedFile(outputFilePath, cadImage, options);
                long saveTime = System.currentTimeMillis() - saveStartTime;
                FileConvertStatusManager.updateProgress(fileName, "文件转换完成", 90);
                // 计算总时间
                long totalTime = System.currentTimeMillis() - totalStartTime;
                // 记录详细的性能信息
                logger.debug("CAD转换详细耗时 - 文件: {}, 验证={}ms, 目录={}ms, 加载={}ms, 光栅化={}ms, 选项={}ms, 保存={}ms, 总耗时={}ms",
                        fileName, validationTime, directoryTime, loadTime,
                        rasterizationTime, optionsTime, saveTime, totalTime);

                logger.info("CAD转换完成: 总耗时: {}ms", totalTime);

                // SVG文件后处理
                if ("svg".equalsIgnoreCase(cadPreviewType)) {
                    if(ConfigConstants.getCadwatermark()){
                        postProcessSvgFile(outputFilePath);
                    }
                }

                // 转换成功，标记为完成
                FileConvertStatusManager.updateProgress(fileName, "转换成功", 100);
                // 短暂延迟后清理状态，给前端一个显示100%的机会
                FileConvertStatusManager.convertSuccess(fileName);


                return true;
            }

        } catch (Exception e) {
            long totalTime = System.currentTimeMillis() - totalStartTime;
            logger.error("CAD转换执行失败: {}, 耗时: {}ms", fileName, totalTime, e);

            // 检查是否已经标记为超时
            FileConvertStatusManager.ConvertStatus status = FileConvertStatusManager.getConvertStatus(fileName);
            if (status == null || status.getStatus() != FileConvertStatusManager.Status.TIMEOUT) {
                FileConvertStatusManager.markError(fileName, "转换失败: " + e.getMessage());
            }

            // 删除可能已创建的不完整文件
            deleteIncompleteFile(outputFilePath);
            return false;

        } finally {
            long cleanupStartTime = System.currentTimeMillis();
            try {
                cleanupResources(source);
            } finally {
                long cleanupTime = System.currentTimeMillis() - cleanupStartTime;
                long totalTime = System.currentTimeMillis() - totalStartTime;
                logger.debug("CAD转换资源清理完成: {}, 清理耗时: {}ms, 总耗时: {}ms",
                        fileName, cleanupTime, totalTime);
            }
        }
    }


    /**
     * 验证输入参数
     */
    private boolean validateInputParameters(String inputFilePath, String outputFilePath,
                                            String cadPreviewType) {
        if (inputFilePath == null || inputFilePath.trim().isEmpty()) {
            logger.error("输入文件路径为空");
            return false;
        }

        File inputFile = new File(inputFilePath);
        if (!inputFile.exists()) {
            logger.error("输入文件不存在: {}", inputFilePath);
            return false;
        }

        if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
            logger.error("输出文件路径为空");
            return false;
        }

        if (!isSupportedPreviewType(cadPreviewType)) {
            logger.error("不支持的预览类型: {}", cadPreviewType);
            return false;
        }

        return true;
    }

    /**
     * 检查是否支持的预览类型
     */
    private boolean isSupportedPreviewType(String previewType) {
        return switch (previewType.toLowerCase()) {
            case "svg", "pdf", "tif", "tiff" -> true;
            default -> false;
        };
    }

    /**
     * 创建输出目录
     */
    private void createOutputDirectoryIfNeeded(String outputFilePath, boolean isCompressFile) {
        if (!isCompressFile) {
            return;
        }
        File outputFile = new File(outputFilePath);
        File parentDir = outputFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw new RuntimeException("无法创建输出目录: " + parentDir.getAbsolutePath());
            }
        }
    }

    /**
     * 创建加载选项
     */
    private LoadOptions createLoadOptions() {
        LoadOptions opts = new LoadOptions();
        opts.setSpecifiedEncoding(CodePages.SimpChinese);
        return opts;
    }

    /**
     * 创建光栅化选项
     */
    private CadRasterizationOptions createRasterizationOptions(Image cadImage) {
        RasterizationQuality quality = new RasterizationQuality();
        RasterizationQualityValue highQuality = RasterizationQualityValue.High;
        quality.setArc(highQuality);
        quality.setHatch(highQuality);
        quality.setText(highQuality);
        quality.setOle(highQuality);
        quality.setObjectsPrecision(highQuality);
        quality.setTextThicknessNormalization(true);

        return getCadRasterizationOptions(cadImage, quality);
    }

    private static CadRasterizationOptions getCadRasterizationOptions(Image cadImage, RasterizationQuality quality) {
        CadRasterizationOptions options = new CadRasterizationOptions();
        options.setBackgroundColor(Color.getWhite());
        options.setPageWidth(cadImage.getWidth());
        options.setPageHeight(cadImage.getHeight());
        options.setUnitType(cadImage.getUnitType());
        options.setAutomaticLayoutsScaling(false);
        options.setNoScaling(false);
        options.setQuality(quality);
        options.setDrawType(CadDrawTypeMode.UseObjectColor);
        options.setExportAllLayoutContent(true);
        options.setVisibilityMode(VisibilityMode.AsScreen);
        return options;
    }

    /**
     * 创建SVG选项
     */
    private SvgOptions createSvgOptions(CadRasterizationOptions rasterizationOptions,
                                        InterruptionTokenSource source) {
        SvgOptions options = new SvgOptions();
        options.setVectorRasterizationOptions(rasterizationOptions);
        options.setInterruptionToken(source.getToken());
        return options;
    }

    /**
     * 创建PDF选项
     */
    private PdfOptions createPdfOptions(CadRasterizationOptions rasterizationOptions,
                                        InterruptionTokenSource source) {
        PdfOptions options = new PdfOptions();
        options.setVectorRasterizationOptions(rasterizationOptions);
        options.setInterruptionToken(source.getToken());
        return options;
    }

    /**
     * 创建TIFF选项
     */
    private TiffOptions createTiffOptions(CadRasterizationOptions rasterizationOptions,
                                          InterruptionTokenSource source) {
        TiffOptions options = new TiffOptions(TiffExpectedFormat.TiffJpegRgb);
        options.setVectorRasterizationOptions(rasterizationOptions);
        options.setInterruptionToken(source.getToken());
        return options;
    }

    /**
     * 保存转换后的文件
     */
    private void saveConvertedFile(String outputFilePath, Image cadImage, Object options)
            throws IOException {
        try (OutputStream outputStream = new FileOutputStream(outputFilePath)) {
            switch (options) {
                case SvgOptions svgOptions -> cadImage.save(outputStream, svgOptions);
                case PdfOptions pdfOptions -> cadImage.save(outputStream, pdfOptions);
                case TiffOptions tiffOptions -> cadImage.save(outputStream, tiffOptions);
                case null, default -> throw new IllegalArgumentException("不支持的选项类型");
            }
        }
    }

    /**
     * 获取最大并发限制
     */
    private int getConcurrentLimit() {
        try {
            int threadCount = ConfigConstants.getCadThread();
            if (threadCount <= 0) {
                return Math.max(1, Runtime.getRuntime().availableProcessors());
            }

            int maxThreads = Runtime.getRuntime().availableProcessors() * 4;
            return Math.min(threadCount, maxThreads);

        } catch (Exception e) {
            logger.error("获取CAD并发限制失败", e);
            return Math.max(1, Runtime.getRuntime().availableProcessors());
        }
    }

    /**
     * 获取转换超时时间
     */
    private long getConversionTimeout() {
        try {
            long timeout = Long.parseLong(ConfigConstants.getCadTimeout());
            if (timeout <= 0) {
                return 300L;
            }
            return timeout;
        } catch (NumberFormatException e) {
            logger.warn("解析CAD转换超时时间失败，使用默认值300秒", e);
            return 300L;
        }
    }

    /**
     * 清理资源
     */
    private void cleanupResources(InterruptionTokenSource source) {
        try {
            if (source != null) {
                source.dispose();
            }
        } catch (Exception e) {
            logger.warn("释放CAD中断令牌资源失败", e);
        }
    }

    /**
     * 删除不完整文件
     */
    private void deleteIncompleteFile(String filePath) {
        File file = new File(filePath);
        if (file.exists() && !file.delete()) {
            logger.warn("无法删除不完整文件: {}", filePath);
        }
    }

    /**
     * SVG文件后处理
     */
    private void postProcessSvgFile(String outputFilePath) {
        try {
            RemoveSvgAdSimple.removeSvgAdFromFile(outputFilePath);
        } catch (Exception e) {
            logger.warn("SVG文件后处理失败: {}", outputFilePath, e);
        }
    }

    /**
     * 强制取消指定文件的转换任务
     * @param fileName 文件名
     * @return true: 取消成功; false: 取消失败或任务不存在
     */
    public boolean cancelConversion(String fileName) {
        Future<?> future = runningTasks.get(fileName);
        if (future != null) {
            boolean cancelled = future.cancel(true);
            if (cancelled) {
                logger.info("成功取消转换任务: {}", fileName);
                runningTasks.remove(fileName);
                FileConvertStatusManager.markError(fileName, "转换已取消");
            }
            return cancelled;
        }
        return false;
    }

    /**
     * 获取正在运行的任务数量
     * @return 正在运行的任务数量
     */
    public int getRunningTaskCount() {
        return runningTasks.size();
    }

    /**
     * 获取所有正在运行的文件名
     * @return 正在运行的文件名集合
     */
    public java.util.Set<String> getRunningTasks() {
        return runningTasks.keySet();
    }

    /**
     * 检查任务是否完成
     * @param fileName 文件名
     * @return true: 已完成; false: 未完成或不存在
     */
    public boolean isTaskCompleted(String fileName) {
        AtomicBoolean completionStatus = taskCompletionStatus.get(fileName);
        if (completionStatus != null) {
            return completionStatus.get();
        }

        Future<?> future = runningTasks.get(fileName);
        if (future != null) {
            return future.isDone();
        }

        return true; // 如果任务不存在，视为已完成
    }

    /**
     * 优雅关闭服务
     */
    @PreDestroy
    public void shutdown() {
        logger.info("开始关闭CAD转换服务，正在运行的任务数: {}", runningTasks.size());

        for (String fileName : runningTasks.keySet()) {
            cancelConversion(fileName);
        }

        if (virtualThreadExecutor != null && !virtualThreadExecutor.isShutdown()) {
            try {
                virtualThreadExecutor.shutdown();
                if (!virtualThreadExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    virtualThreadExecutor.shutdownNow();
                }
                logger.info("CAD转换服务已关闭");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                virtualThreadExecutor.shutdownNow();
            }
        }
    }
}