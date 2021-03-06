package cn.keking.service;

import cn.keking.config.ConfigConstants;
import cn.keking.model.FileType;
import cn.keking.utils.KkFileUtils;
import cn.keking.web.filter.BaseUrlFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;

import java.io.*;
import java.math.BigDecimal;
import java.text.CollationKey;
import java.text.Collator;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author yudian-it
 * create 2017/11/27
 */
@Component
public class CompressFileReader {

    private static final Pattern pattern = Pattern.compile("^\\d+");
    private final FileHandlerService fileHandlerService;
    private final String fileDir = ConfigConstants.getFileDir();
    private final ExecutorService executors = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    public CompressFileReader(FileHandlerService fileHandlerService) {
        this.fileHandlerService = fileHandlerService;
    }

    public String readZipFile(String filePath, String fileKey) {
        String archiveSeparator = "/";
        Map<String, FileNode> appender = new HashMap<>();
        List<String> imgUrls = new LinkedList<>();
        String baseUrl = BaseUrlFilter.getBaseUrl();
        String archiveFileName = fileHandlerService.getFileNameFromPath(filePath);
        try {
            ZipFile zipFile = new ZipFile(filePath, KkFileUtils.getFileEncode(filePath));
            Enumeration<ZipArchiveEntry> entries = zipFile.getEntries();
            // ??????
            entries = sortZipEntries(entries);
            List<Map<String, ZipArchiveEntry>> entriesToBeExtracted = new LinkedList<>();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String fullName = entry.getName();
                int level = fullName.split(archiveSeparator).length;
                // ?????????
                String originName = getLastFileName(fullName, archiveSeparator);
                String childName = level + "_" + originName;
                boolean directory = entry.isDirectory();
                if (!directory) {
                    childName = archiveFileName + "_" + originName;
                    entriesToBeExtracted.add(Collections.singletonMap(childName, entry));
                }
                String parentName = getLast2FileName(fullName, archiveSeparator, archiveFileName);
                parentName = (level - 1) + "_" + parentName;
                FileType type = FileType.typeFromUrl(childName);
                if (type.equals(FileType.PICTURE)) {//?????????????????????????????????
                    imgUrls.add(baseUrl + childName);
                }
                FileNode node = new FileNode(originName, childName, parentName, new ArrayList<>(), directory, fileKey);
                addNodes(appender, parentName, node);
                appender.put(childName, node);
            }
            // ????????????????????????????????????
            executors.submit(new ZipExtractorWorker(entriesToBeExtracted, zipFile, filePath));
            fileHandlerService.putImgCache(fileKey, imgUrls);
            return new ObjectMapper().writeValueAsString(appender.get(""));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private Enumeration<ZipArchiveEntry> sortZipEntries(Enumeration<ZipArchiveEntry> entries) {
        List<ZipArchiveEntry> sortedEntries = new LinkedList<>();
        while (entries.hasMoreElements()) {
            sortedEntries.add(entries.nextElement());
        }
        sortedEntries.sort(Comparator.comparingInt(o -> o.getName().length()));
        return Collections.enumeration(sortedEntries);
    }

    public String unRar(String filePath, String fileKey) {
        Map<String, FileNode> appender = new HashMap<>();
        List<String> imgUrls = new ArrayList<>();
        String baseUrl = BaseUrlFilter.getBaseUrl();
        try {
            Archive archive = new Archive(new FileInputStream(filePath));
            List<FileHeader> headers = archive.getFileHeaders();
            headers = sortedHeaders(headers);
            String archiveFileName = fileHandlerService.getFileNameFromPath(filePath);
            List<Map<String, FileHeader>> headersToBeExtracted = new ArrayList<>();
            for (FileHeader header : headers) {
                String fullName;
                if (header.isUnicode()) {
                    fullName = header.getFileNameW();
                } else {
                    fullName = header.getFileNameString();
                }
                // ?????????
                String originName = getLastFileName(fullName, "\\");
                String childName = originName;
                boolean directory = header.isDirectory();
                if (!directory) {
                    childName = archiveFileName + "_" + originName;
                    headersToBeExtracted.add(Collections.singletonMap(childName, header));
                }
                String parentName = getLast2FileName(fullName, "\\", archiveFileName);
                FileType type = FileType.typeFromUrl(childName);
                if (type.equals(FileType.PICTURE)) {//?????????????????????????????????
                    imgUrls.add(baseUrl + childName);
                }
                FileNode node = new FileNode(originName, childName, parentName, new ArrayList<>(), directory, fileKey);
                addNodes(appender, parentName, node);
                appender.put(childName, node);
            }
            executors.submit(new RarExtractorWorker(headersToBeExtracted, archive, filePath));
            fileHandlerService.putImgCache(fileKey, imgUrls);
            return new ObjectMapper().writeValueAsString(appender.get(""));
        } catch (RarException | IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    public String read7zFile(String filePath, String fileKey) {
        String archiveSeparator = "/";
        Map<String, FileNode> appender = new HashMap<>();
        List<String> imgUrls = new ArrayList<>();
        String baseUrl = BaseUrlFilter.getBaseUrl();
        String archiveFileName = fileHandlerService.getFileNameFromPath(filePath);
        try {
            SevenZFile zipFile = new SevenZFile(new File(filePath));
            Iterable<SevenZArchiveEntry> entries = zipFile.getEntries();
            // ??????
            Enumeration<SevenZArchiveEntry> newEntries = sortSevenZEntries(entries);
            List<Map<String, SevenZArchiveEntry>> entriesToBeExtracted = new ArrayList<>();
            while (newEntries.hasMoreElements()) {
                SevenZArchiveEntry entry = newEntries.nextElement();
                String fullName = entry.getName();
                int level = fullName.split(archiveSeparator).length;
                // ?????????
                String originName = getLastFileName(fullName, archiveSeparator);
                String childName = level + "_" + originName;
                boolean directory = entry.isDirectory();
                if (!directory) {
                    childName = archiveFileName + "_" + originName;
                    entriesToBeExtracted.add(Collections.singletonMap(childName, entry));
                }
                String parentName = getLast2FileName(fullName, archiveSeparator, archiveFileName);
                parentName = (level - 1) + "_" + parentName;
                FileType type = FileType.typeFromUrl(childName);
                if (type.equals(FileType.PICTURE)) {//?????????????????????????????????
                    imgUrls.add(baseUrl + childName);
                }
                FileNode node = new FileNode(originName, childName, parentName, new ArrayList<>(), directory, fileKey);
                addNodes(appender, parentName, node);
                appender.put(childName, node);
            }
            // ????????????????????????????????????
            executors.submit(new SevenZExtractorWorker(entriesToBeExtracted, filePath));
            fileHandlerService.putImgCache(fileKey, imgUrls);
            return new ObjectMapper().writeValueAsString(appender.get(""));
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }


    private Enumeration<SevenZArchiveEntry> sortSevenZEntries(Iterable<SevenZArchiveEntry> entries) {
        List<SevenZArchiveEntry> sortedEntries = new ArrayList<>();
        for (SevenZArchiveEntry entry : entries) {
            sortedEntries.add(entry);
        }
        return Collections.enumeration(sortedEntries);
    }

    private void addNodes(Map<String, FileNode> appender, String parentName, FileNode node) {
        if (appender.containsKey(parentName)) {
            appender.get(parentName).getChildList().add(node);
            appender.get(parentName).getChildList().sort(sortComparator);
        } else {
            // ?????????
            FileNode nodeRoot = new FileNode(parentName, parentName, "", new ArrayList<>(), true);
            nodeRoot.getChildList().add(node);
            appender.put("", nodeRoot);
            appender.put(parentName, nodeRoot);
        }
    }

    private List<FileHeader> sortedHeaders(List<FileHeader> headers) {
        List<FileHeader> sortedHeaders = new ArrayList<>();
        Map<Integer, FileHeader> mapHeaders = new TreeMap<>();
        headers.forEach(header -> mapHeaders.put(new Integer(0).equals(header.getFileNameW().length()) ? header.getFileNameString().length() : header.getFileNameW().length(), header));
        for (Map.Entry<Integer, FileHeader> entry : mapHeaders.entrySet()) {
            for (FileHeader header : headers) {
                if (entry.getKey().equals(new Integer(0).equals(header.getFileNameW().length()) ? header.getFileNameString().length() : header.getFileNameW().length())) {
                    sortedHeaders.add(header);
                }
            }
        }
        return sortedHeaders;
    }

    private static String getLast2FileName(String fullName, String seperator, String rootName) {
        if (fullName.endsWith(seperator)) {
            fullName = fullName.substring(0, fullName.length() - 1);
        }
        // 1.??????????????????
        int endIndex = fullName.lastIndexOf(seperator);
        String leftPath = fullName.substring(0, endIndex == -1 ? 0 : endIndex);
        if (leftPath.length() > 1) {
            // 2.?????????????????????
            return getLastFileName(leftPath, seperator);
        } else {
            return rootName;
        }
    }

    private static String getLastFileName(String fullName, String seperator) {
        if (fullName.endsWith(seperator)) {
            fullName = fullName.substring(0, fullName.length() - 1);
        }
        String newName = fullName;
        if (fullName.contains(seperator)) {
            newName = fullName.substring(fullName.lastIndexOf(seperator) + 1);
        }
        return newName;
    }

    public static Comparator<FileNode> sortComparator = new Comparator<FileNode>() {
        final Collator cmp = Collator.getInstance(Locale.US);

        @Override
        public int compare(FileNode o1, FileNode o2) {
            // ???????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
            BigDecimal num1, num2;
            if (null != (num1 = isStartNumber(o1))
                    && null != (num2 = isStartNumber(o2))) {
                return num1.subtract(num2).intValue();
            }
            CollationKey c1 = cmp.getCollationKey(o1.getOriginName());
            CollationKey c2 = cmp.getCollationKey(o2.getOriginName());
            return cmp.compare(c1.getSourceString(), c2.getSourceString());
        }
    };

    private static BigDecimal isStartNumber(FileNode src) {
        Matcher matcher = pattern.matcher(src.getOriginName());
        if (matcher.find()) {
            return new BigDecimal(matcher.group());
        }
        return null;
    }

    public static class FileNode {

        private String originName;
        private String fileName;
        private String parentFileName;
        private boolean directory;
        //???????????????????????????
        private String fileKey;
        private List<FileNode> childList;

        public FileNode(String originName, String fileName, String parentFileName, List<FileNode> childList, boolean directory) {
            this.originName = originName;
            this.fileName = fileName;
            this.parentFileName = parentFileName;
            this.childList = childList;
            this.directory = directory;
        }

        public FileNode(String originName, String fileName, String parentFileName, List<FileNode> childList, boolean directory, String fileKey) {
            this.originName = originName;
            this.fileName = fileName;
            this.parentFileName = parentFileName;
            this.childList = childList;
            this.directory = directory;
            this.fileKey = fileKey;
        }

        public String getFileKey() {
            return fileKey;
        }

        public void setFileKey(String fileKey) {
            this.fileKey = fileKey;
        }

        public String getFileName() {
            return fileName;
        }

        public void setFileName(String fileName) {
            this.fileName = fileName;
        }

        public String getParentFileName() {
            return parentFileName;
        }

        public void setParentFileName(String parentFileName) {
            this.parentFileName = parentFileName;
        }

        public List<FileNode> getChildList() {
            return childList;
        }

        public void setChildList(List<FileNode> childList) {
            this.childList = childList;
        }

        @Override
        public String toString() {
            try {
                return new ObjectMapper().writeValueAsString(this);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
                return "";
            }
        }

        public String getOriginName() {
            return originName;
        }

        public void setOriginName(String originName) {
            this.originName = originName;
        }

        public boolean isDirectory() {
            return directory;
        }

        public void setDirectory(boolean directory) {
            this.directory = directory;
        }
    }

    class ZipExtractorWorker implements Runnable {

        private final List<Map<String, ZipArchiveEntry>> entriesToBeExtracted;
        private final ZipFile zipFile;
        private final String filePath;

        public ZipExtractorWorker(List<Map<String, ZipArchiveEntry>> entriesToBeExtracted, ZipFile zipFile, String filePath) {
            this.entriesToBeExtracted = entriesToBeExtracted;
            this.zipFile = zipFile;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            for (Map<String, ZipArchiveEntry> entryMap : entriesToBeExtracted) {
                String childName = entryMap.keySet().iterator().next();
                ZipArchiveEntry entry = entryMap.values().iterator().next();
                try {
                    extractZipFile(childName, zipFile.getInputStream(entry));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            try {
                zipFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            KkFileUtils.deleteFileByPath(filePath);
        }

        private void extractZipFile(String childName, InputStream zipFile) {
            String outPath = fileDir + childName;
            try (OutputStream ot = new FileOutputStream(outPath)) {
                byte[] inByte = new byte[1024];
                int len;
                while ((-1 != (len = zipFile.read(inByte)))) {
                    ot.write(inByte, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class SevenZExtractorWorker implements Runnable {

        private final List<Map<String, SevenZArchiveEntry>> entriesToBeExtracted;
        private final String filePath;

        public SevenZExtractorWorker(List<Map<String, SevenZArchiveEntry>> entriesToBeExtracted, String filePath) {
            this.entriesToBeExtracted = entriesToBeExtracted;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            try {
                SevenZFile sevenZFile = new SevenZFile(new File(filePath));
                SevenZArchiveEntry entry = sevenZFile.getNextEntry();
                while (entry != null) {
                    if (entry.isDirectory()) {
                        entry = sevenZFile.getNextEntry();
                        continue;
                    }
                    String childName = "default_file";
                    SevenZArchiveEntry entry1;
                    for (Map<String, SevenZArchiveEntry> entryMap : entriesToBeExtracted) {
                        childName = entryMap.keySet().iterator().next();
                        entry1 = entryMap.values().iterator().next();
                        if (entry.getName().equals(entry1.getName())) {
                            break;
                        }
                    }
                    FileOutputStream out = new FileOutputStream(fileDir + childName);
                    byte[] content = new byte[(int) entry.getSize()];
                    sevenZFile.read(content, 0, content.length);
                    out.write(content);
                    out.close();
                    entry = sevenZFile.getNextEntry();
                }
                sevenZFile.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            KkFileUtils.deleteFileByPath(filePath);
        }
    }

    class RarExtractorWorker implements Runnable {
        private final List<Map<String, FileHeader>> headersToBeExtracted;
        private final Archive archive;
        /**
         * ?????????????????????
         */
        private final String filePath;

        public RarExtractorWorker(List<Map<String, FileHeader>> headersToBeExtracted, Archive archive, String filePath) {
            this.headersToBeExtracted = headersToBeExtracted;
            this.archive = archive;
            this.filePath = filePath;
        }

        @Override
        public void run() {
            for (Map<String, FileHeader> entryMap : headersToBeExtracted) {
                String childName = entryMap.keySet().iterator().next();
                extractRarFile(childName, entryMap.values().iterator().next(), archive);
            }
            try {
                archive.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            KkFileUtils.deleteFileByPath(filePath);
        }

        private void extractRarFile(String childName, FileHeader header, Archive archive) {
            String outPath = fileDir + childName;
            try (OutputStream ot = new FileOutputStream(outPath)) {
                archive.extractFile(header, ot);
            } catch (IOException | RarException e) {
                e.printStackTrace();
            }
        }
    }
}
