/*
 * Copyright 2015-2015, Fabio Melo Pfeifer
 * 
 * This file is part of Indexador e Processador de Evidencias Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.mg.udi.gpinf.whatsappextractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.tika.config.Field;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TemporaryResources;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.XHTMLContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import dpf.mg.udi.gpinf.vcardparser.VCardParser;
import dpf.mg.udi.gpinf.whatsappextractor.LinkDownloader.URLnotFound;
import dpf.mg.udi.gpinf.whatsappextractor.Message.MessageType;
import dpf.sp.gpinf.indexer.parsers.IndexerDefaultParser;
import dpf.sp.gpinf.indexer.parsers.jdbc.SQLite3DBParser;
import dpf.sp.gpinf.indexer.parsers.jdbc.SQLite3Parser;
import dpf.sp.gpinf.indexer.parsers.util.ItemInfo;
import dpf.sp.gpinf.indexer.parsers.util.PhoneParsingConfig;
import dpf.sp.gpinf.indexer.util.EmptyInputStream;
import dpf.sp.gpinf.indexer.util.SimpleHTMLEncoder;
import iped3.IItem;
import iped3.io.IItemBase;
import iped3.io.SeekableInputStream;
import iped3.search.IItemSearcher;
import iped3.util.BasicProps;
import iped3.util.ExtraProperties;

/**
 * Parser para banco de dados do WhatsApp
 *
 * @author Fabio Melo Pfeifer <pfeifer.fmp@dpf.gov.br>
 */
public class WhatsAppParser extends SQLite3DBParser {

    /**
     * 
     */
    private static Logger logger = LoggerFactory.getLogger(WhatsAppParser.class);

    private static final long serialVersionUID = 1L;

    public static final String WHATSAPP = "WhatsApp";

    public static final MediaType WA_USER_XML = MediaType.application("x-whatsapp-user-xml"); //$NON-NLS-1$

    public static final MediaType WA_USER_PLIST = MediaType.application("x-whatsapp-user-plist"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_ACCOUNT = MediaType.application("x-whatsapp-account"); //$NON-NLS-1$

    public static final MediaType MSG_STORE = MediaType.application("x-whatsapp-db"); //$NON-NLS-1$

    public static final MediaType MSG_STORE_2 = MediaType.application("x-whatsapp-db-f"); //$NON-NLS-1$

    public static final MediaType WA_DB = MediaType.application("x-whatsapp-wadb"); //$NON-NLS-1$

    public static final MediaType CHAT_STORAGE = MediaType.application("x-whatsapp-chatstorage"); //$NON-NLS-1$

    public static final MediaType CONTACTS_V2 = MediaType.application("x-whatsapp-contactsv2"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_CHAT = MediaType.parse("application/x-whatsapp-chat"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_CONTACT = MediaType.parse("contact/x-whatsapp-contact"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_MESSAGE = MediaType.parse("message/x-whatsapp-message"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_ATTACHMENT = MediaType.parse("message/x-whatsapp-attachment"); //$NON-NLS-1$

    public static final MediaType WHATSAPP_CALL = MediaType.parse("call/x-whatsapp-call"); //$NON-NLS-1$

    public static final String SHA256_ENABLED_SYSPROP = "IsSha256Enabled"; //$NON-NLS-1$

    public static final String DOWNLOAD_MEDIA_FILES_PROP = "downloadWhatsAppMediaProp";

    private static final AtomicBoolean sha256Checked = new AtomicBoolean();

    // workaround to show message type before caption (values are shown in sort
    // order)
    private static final String MESSAGE_TYPE_PREFIX = "! "; //$NON-NLS-1$

    private static final int MESSAGE_SEARCH_BATCH_SIZE = 512;

    // a global set to prevent redownload files;
    private static final Set<String> hashesDownloaded = Collections.synchronizedSet(new HashSet<>());

    private static final Pattern MSGSTORE_BKP = Pattern.compile("msgstore-\\d{4}-\\d{2}-\\d{2}"); //$NON-NLS-1$
    private static final String MSGSTORE_CRYPTO = "msgstore.db.crypt"; //$NON-NLS-1$
    private static final String IS_BACKUP_FROM = "isBackupFrom";

    private static final Map<Integer, WhatsAppContext> dbsFound = new ConcurrentHashMap<>();

    private static final int POOL_SIZE = 20;

    private static ExecutorService executor;

    private static final AtomicInteger backupsMerged = new AtomicInteger();

    private static boolean dbsSearchedFor = false;
    private static int dbsSearchedForAndAdded = 0;

    private static Set<MediaType> SUPPORTED_TYPES = MediaType.set(MSG_STORE, WA_DB, CHAT_STORAGE, CONTACTS_V2,
            WA_USER_XML, WA_USER_PLIST, MSG_STORE_2);

    private static final Map<String, WAContactsDirectory> contactsDirectoriesMap = new ConcurrentHashMap<>();

    private SQLite3Parser sqliteParser = new SQLite3Parser();

    private boolean extractMessages = true;
    private boolean linkMediasByNameAndApproxSizeFallback = true;
    private boolean mergeBackups = false;
    private int downloadConnectionTimeout = 500;
    private int downloadReadTimeout = 500;

    @Override
    public Set<MediaType> getSupportedTypes(ParseContext arg0) {
        if (!sha256Checked.getAndSet(true)) {
            if (!Boolean.valueOf(System.getProperty(SHA256_ENABLED_SYSPROP, "false"))) { //$NON-NLS-1$
                logger.error("SHA-256 is disabled. WhatsAppParser needs it to link attachments to chats!"); //$NON-NLS-1$
            }
        }
        return SUPPORTED_TYPES;
    }

    @Field
    public void setExtractMessages(boolean extractMessages) {
        this.extractMessages = extractMessages;
    }

    @Field
    public void setMergeBackups(boolean mergeBackups) {
        this.mergeBackups = mergeBackups;
    }

    @Field
    public void setLinkMediasByNameAndApproxSizeFallback(boolean linkMediasByNameAndApproxSizeFallback) {
        this.linkMediasByNameAndApproxSizeFallback = linkMediasByNameAndApproxSizeFallback;
    }

    @Field
    public void setDownloadConnectionTimeout(int downloadConnectionTimeout) {
        this.downloadConnectionTimeout = downloadConnectionTimeout;
    }

    @Field
    public void setDownloadReadTimeout(int downloadReadTimeout) {
        this.downloadReadTimeout = downloadReadTimeout;
    }

    private boolean isDownloadMediaFilesEnabled() {
        return Boolean.valueOf(System.getProperty(DOWNLOAD_MEDIA_FILES_PROP));
    }

    @Override
    public void parse(InputStream stream, ContentHandler handler, Metadata metadata, ParseContext context)
            throws IOException, SAXException, TikaException {

        IItemBase item = context.get(IItemBase.class);
        if (PhoneParsingConfig.isExternalPhoneParsersOnly() && PhoneParsingConfig.isFromUfdrDatasourceReader(item)) {
            return;
        }

        String mimetype = metadata.get(IndexerDefaultParser.INDEXER_CONTENT_TYPE);
        if (mimetype == null) {
            mimetype = metadata.get(Metadata.CONTENT_TYPE);
        }
        try (TemporaryResources tmp = new TemporaryResources()) {
            stream = TikaInputStream.get(stream, tmp);
            if (mimetype.equals(WA_USER_XML.toString())) {
                parseWhatsAppAccount(stream, context, handler, true);
            } else if (mimetype.equals(WA_USER_PLIST.toString())) {
                parseWhatsAppAccount(stream, context, handler, false);
            } else if (mimetype.equals(MSG_STORE.toString())) {
                if (mergeBackups || isDownloadMediaFilesEnabled())
                    parseAndCheckIfIsMainDb(stream, handler, metadata, context, new ExtractorAndroidFactory());
                else
                    parseWhatsappMessages(stream, handler, metadata, context, new ExtractorAndroidFactory());
            } else if (mimetype.equals(WA_DB.toString())) {
                parseWhatsAppContacts(stream, handler, metadata, context, new ExtractorAndroidFactory());
            } else if (mimetype.equals(CHAT_STORAGE.toString())) {
                parseWhatsappMessages(stream, handler, metadata, context, new ExtractorIOSFactory());
            } else if (mimetype.equals(CONTACTS_V2.toString())) {
                parseWhatsAppContacts(stream, handler, metadata, context, new ExtractorIOSFactory());
            } else if (mimetype.equals(MSG_STORE_2.toString())) {
                mergeParsedDBsAndOutputResults(stream, handler, metadata, context, new ExtractorAndroidFactory());
            }
        } catch (Exception e) {
            // log all whatsapp exceptions
            e.printStackTrace();
            throw e;
        }

    }

    private void createReport(List<Chat> chatList, IItemSearcher searcher, WAContactsDirectory contacts,
            ContentHandler handler, EmbeddedDocumentExtractor extractor, WAAccount account, File dbPath,
            ParseContext context) throws Exception {
        int chatVirtualId = 0;
        HashMap<String, String> cache = new HashMap<>();
        for (Chat c : chatList) {
            getAvatar(searcher, c.getRemote());
            searchMediaFilesForMessagesInBatches(c.getMessages(), searcher, handler, extractor, dbPath, context, null);
            int frag = 0;
            int firstMsg = 0;
            ReportGenerator reportGenerator = new ReportGenerator();
            byte[] bytes = reportGenerator.generateNextChatHtml(c, contacts, account);
            while (bytes != null) {
                Metadata chatMetadata = new Metadata();
                int nextMsg = reportGenerator.getNextMsgNum();

                List<Message> msgSubset = c.getMessages().subList(firstMsg, nextMsg);
                storeLinkedHashes(msgSubset, chatMetadata, searcher);

                // condition to avoid duplicate locations being saved in chat & messages
                if (!extractMessages) {
                    storeLocations(msgSubset, chatMetadata);
                }
                firstMsg = nextMsg;
                byte[] nextBytes = reportGenerator.generateNextChatHtml(c, contacts, account);

                String chatName = c.getTitle();
                if (frag > 0 || nextBytes != null)
                    chatName += "_" + frag++; //$NON-NLS-1$

                chatMetadata.set(TikaCoreProperties.TITLE, chatName);
                chatMetadata.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_CHAT.toString());
                chatMetadata.set(ExtraProperties.ITEM_VIRTUAL_ID, Integer.toString(chatVirtualId));
                chatMetadata.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());

                if (extractMessages && msgSubset.size() > 0) {
                    chatMetadata.set(BasicProps.HASCHILD, Boolean.TRUE.toString());
                }
                if (account != null) {
                    String local = formatContact(account, cache);
                    chatMetadata.add(ExtraProperties.PARTICIPANTS, local);
                }
                if (c.isGroupChat()) {
                    for (WAContact member : c.getGroupmembers()) {
                        chatMetadata.add(ExtraProperties.PARTICIPANTS, formatContact(member, cache));
                    }
                    // string formatted as {creator's phone number}-{creation time}@g.us
                    chatMetadata.add(ExtraProperties.GROUP_ID, c.getRemote().getFullId());
                } else {
                    if (c.getRemote() != null) {
                        chatMetadata.add(ExtraProperties.PARTICIPANTS, formatContact(c.getRemote(), cache));
                    }
                }

                ByteArrayInputStream chatStream = new ByteArrayInputStream(bytes);
                extractor.parseEmbedded(chatStream, handler, chatMetadata, false);
                bytes = nextBytes;

                if (extractMessages) {
                    extractMessages(chatName, c, msgSubset, account, contacts, chatVirtualId++, handler, extractor,
                            cache);
                }
            }
            // clear heavy items references (possibly with thumbs loaded)
            c.getMessages().stream().forEach(m -> m.setMediaItem(null));
        }

    }

    private void parseWhatsappMessages(InputStream stream, ContentHandler handler, Metadata metadata,
            ParseContext context, ExtractorFactory extFactory) throws IOException, SAXException, TikaException {

        extFactory.setConnectionParams(stream, metadata, context, this);
        EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                new ParsingEmbeddedDocumentExtractor(context));
        IItemSearcher searcher = context.get(IItemSearcher.class);
        TemporaryResources tmp = new TemporaryResources();

        if (extractor.shouldParseEmbedded(metadata)) {
            TikaInputStream tis = TikaInputStream.get(stream, tmp);
            try {
                ItemInfo itemInfo = context.get(ItemInfo.class);
                String filePath = null;
                if (itemInfo != null) {
                    filePath = itemInfo.getPath();
                }
                WAContactsDirectory contacts = getWAContactsDirectoryForPath(filePath, searcher, extFactory.getClass());

                String dbPath = ((ItemInfo) context.get(ItemInfo.class)).getPath();
                WAAccount account = getUserAccount(searcher, dbPath, extFactory instanceof ExtractorAndroidFactory);

                Extractor waExtractor = extFactory.createMessageExtractor(tis.getFile(), contacts, account);
                List<Chat> chatList = waExtractor.getChatList();
                createReport(chatList, searcher, contacts, handler, extractor, account, tis.getFile(), context);

            } catch (Exception e) {
                sqliteParser.parse(tis, handler, metadata, context);
                if (e instanceof TikaException)
                    throw (TikaException) e;
                else
                    throw new TikaException("WAExtractorException Exception", e); //$NON-NLS-1$

            } finally {
                tmp.dispose();
            }
        }
    }

    private void parseDB(WhatsAppContext wcontext, Metadata metadata, ParseContext context, ExtractorFactory extFactory)
            throws IOException, SAXException, TikaException {

        try {
            IItemSearcher searcher = context.get(IItemSearcher.class);
            WAContactsDirectory contacts = getWAContactsDirectoryForPath(wcontext.getItem().getPath(), searcher,
                    extFactory.getClass());

            WAAccount account = getUserAccount(searcher, wcontext.getItem().getPath(),
                    extFactory instanceof ExtractorAndroidFactory);

            wcontext.setChalist(extractChatList(wcontext, extFactory, metadata, context, contacts, account));

        } catch (Exception e) {
            if (e instanceof TikaException)
                throw (TikaException) e;
            else
                throw new TikaException("WAExtractorException Exception", e); //$NON-NLS-1$
        }

    }

    private static final void waitDownloads(List<Future<?>> futures) {
        try {
            for (Future<?> f : futures) {
                f.get();
            }
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
        }

    }

    private ExecutorService getExecutor() {
        if (executor == null) {
            synchronized (this.getClass()) {
                if (executor == null) {
                    executor = Executors.newFixedThreadPool(POOL_SIZE);
                }
            }
        }
        return executor;
    }

    public static final void clearStaticResources() {
        try {
            Message.closeStaticResources();
        } catch (IOException e) {
            logger.warn("Fail to clear resources from WhatsAppParser", e);
        }
        if (executor != null) {
            executor.shutdown();
        }
    }

    private void parseAndCheckIfIsMainDb(InputStream stream, ContentHandler handler, Metadata metadata,
            ParseContext context, ExtractorFactory extFactory) throws IOException, SAXException, TikaException {

        WhatsAppContext wcontext = new WhatsAppContext(false, context.get(IItemBase.class));

        parseDB(wcontext, metadata, context, extFactory);
        if (isDownloadMediaFilesEnabled()) {
            EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                    new ParsingEmbeddedDocumentExtractor(context));
            IItemSearcher searcher = context.get(IItemSearcher.class);
            ArrayList<Future<?>> futures = new ArrayList<>();
            AtomicInteger downloadedFiles = new AtomicInteger(0);
            for (Chat c : wcontext.getChalist()) {
                futures.addAll(searchMediaFilesForMessagesInBatches(c.getMessages(), searcher, handler, extractor,
                        wcontext.getItem().getTempFile(), context, downloadedFiles));
            }
            waitDownloads(futures);
            if (downloadedFiles.get() > 0) {
                logger.info("Downloaded {} files from {}", downloadedFiles.get(), wcontext.getItem().getName());
            }
        }

        checkIfIsMainDBAndStore(wcontext);

        metadata.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, MSG_STORE_2.toString());
    }

    private static boolean checkIfIsMainDBAndStore(WhatsAppContext wcontext) {
        IItemBase item = wcontext.getItem();
        if (!MSGSTORE_BKP.matcher(item.getName()).find() && !item.getPath().contains(MSGSTORE_CRYPTO)) {
            wcontext.setMainDB(true);
            wcontext.setBackup(false);
        }
        return dbsFound.putIfAbsent(item.getId(), wcontext) == null;
    }

    private List<Chat> extractChatList(WhatsAppContext wcontext, ExtractorFactory extFactory, Metadata metadata,
            ParseContext context, WAContactsDirectory contacts, WAAccount account)
            throws WAExtractorException, IOException {
        try (TemporaryResources tmp = new TemporaryResources()) {
            TikaInputStream tis = TikaInputStream.get(wcontext.getItem().getSeekableInputStream(), tmp);
            File tempFile = tis.getFile();
            extFactory.setConnectionParams(tis, metadata, context, this);
            Extractor waExtractor = extFactory.createMessageExtractor(tempFile, contacts, account);
            return waExtractor.getChatList();
        }
    }

    private static synchronized void findOtherDBS(IItemSearcher searcher) {
        if (dbsSearchedFor) {
            return;
        }
        String query = BasicProps.CONTENTTYPE + ":\"" + MSG_STORE + "\" OR " + BasicProps.CONTENTTYPE + ":\"" //$NON-NLS-1$ //$NON-NLS-2$
                + MSG_STORE_2 + "\"";
        List<IItemBase> result = dpf.sp.gpinf.indexer.parsers.util.Util.getItems(query, searcher);
        for (IItemBase it : result) {
            WhatsAppContext wcontext = new WhatsAppContext(false, it);
            if (checkIfIsMainDBAndStore(wcontext)) {
                dbsSearchedForAndAdded++;
            }
        }
        dbsSearchedFor = true;
    }

    private void addBackupMessage(WhatsAppContext item, IItemBase main, XHTMLContentHandler xhtml) throws SAXException {
        IItem i = (IItem) item.getItem();
        i.getMetadata().set(IS_BACKUP_FROM, main.getExtraAttribute(ExtraProperties.GLOBAL_ID).toString());
        xhtml.startDocument();
        xhtml.characters("Backup from " + main.getPath());
        xhtml.endDocument();
    }

    private void mergeParsedDBsAndOutputResults(InputStream stream, ContentHandler handler, Metadata metadata,
            ParseContext context, ExtractorFactory extFactory) throws IOException, SAXException, TikaException {

        IItemBase DB = context.get(IItemBase.class);
        IItemSearcher searcher = context.get(IItemSearcher.class);

        // this call is needed when processing was stopped and is being resumed, so DBs
        // list and parsing results won't be on memory yet
        findOtherDBS(searcher);

        WhatsAppContext wcontext = dbsFound.get(DB.getId());
        if (wcontext != null && wcontext.getChalist() == null) {
            // if not parsed yet, parse the DB here
            synchronized (wcontext) {
                if (wcontext.getChalist() == null) {
                    parseDB(wcontext, metadata, context, extFactory);
                }
            }
        }

        // parse DBs found above
        for (WhatsAppContext other : dbsFound.values().toArray(new WhatsAppContext[0])) {
            if (other == wcontext)
                continue;
            synchronized (other) {
                if (other.getChalist() == null) {
                    // if not parsed yet, parse the DB here
                    try {
                        parseDB(other, metadata, context, extFactory);
                    } catch (Exception e) {
                        other.setMainDB(false);
                        other.setBackup(false);
                        logger.warn("Could not parse DB {} ({} bytes): {}", other.getItem().getPath(),
                                other.getItem().getLength(), e.toString());
                        logger.debug("", e);
                    }
                }
            }
        }

        List<WhatsAppContext> dbsFoundList = new ArrayList<>(dbsFound.values());

        // this is used to sort backups by decreasing modifiedDate (from name) order, so
        // the most recent backup will be used when merging if a missing chat/message is
        // found, giving a better idea about when the user deleted the chat/message
        Collections.sort(dbsFoundList, new Comparator<WhatsAppContext>() {
            @Override
            public int compare(WhatsAppContext o1, WhatsAppContext o2) {
                return -o1.getItem().getName().compareTo(o2.getItem().getName());
            }
        });

        if (wcontext == null) {
            // MakePreviewTask enters here for main dbs and backups without main db
            return;
        }

        try (TemporaryResources tmp = new TemporaryResources()) {

            WAContactsDirectory contacts = getWAContactsDirectoryForPath(DB.getPath(), searcher, extFactory.getClass());

            WAAccount account = getUserAccount(searcher, DB.getPath(), extFactory instanceof ExtractorAndroidFactory);

            File tmpDB = TikaInputStream.get(stream, tmp).getFile();

            stream.skip(wcontext.getItem().getLength());

            List<Chat> dbChatList = wcontext.getChalist();
            // if merge is not enable create a report for every db
            if (!mergeBackups) {
                EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                        new ParsingEmbeddedDocumentExtractor(context));
                createReport(dbChatList, searcher, contacts, handler, extractor, account, tmpDB, context);

                dbsFound.remove(DB.getId());
                return;
            }

            if (wcontext.isBackup() && wcontext.getMainDBItem() != null) {
                // MakePreviewTask enters here later for backups, output preview message
                addBackupMessage(wcontext, wcontext.getMainDBItem(), new XHTMLContentHandler(handler, metadata));
                return;
            }

            for (WhatsAppContext db : dbsFoundList) {
                // skip current DB
                if (db.getItem().getId() == wcontext.getItem().getId()) {
                    continue;
                }
                WhatsAppContext mainDb;
                WhatsAppContext other;

                if (wcontext.isMainDB() && !db.isMainDB()) {
                    mainDb = wcontext;
                    other = db;
                } else if (!wcontext.isMainDB() && db.isMainDB()) {
                    mainDb = db;
                    other = wcontext;
                } else {
                    // skip if both are mainDB or if both are backups
                    continue;
                }

                // new instance to avoid threading issues
                List<Chat> mainDBChatList = new ArrayList<Chat>();
                if (mainDb.getChalist() != null) {
                    mainDBChatList.addAll(mainDb.getChalist());
                }
                ChatMerge cm = new ChatMerge(mainDBChatList, other.getItem().getName());

                if (cm.isBackup(other.getChalist())) {
                    if (wcontext == mainDb) {
                        // merge backup in the main chat list
                        int numMsgRecovered = cm.mergeChatList(other.getChalist());
                        logger.info("Recovered {} messages from {}", numMsgRecovered, other.getItem().getPath()); //$NON-NLS-1$
                        mainDb.setChalist(mainDBChatList);
                        dbChatList = mainDBChatList;
                    }
                    if (wcontext == other) {
                        other.setBackup(true);
                        other.setMainDBItem(mainDb.getItem());
                        // output from which main DB is this backup from
                        addBackupMessage(wcontext, wcontext.getMainDBItem(),
                                new XHTMLContentHandler(handler, metadata));
                        backupsMerged.incrementAndGet();
                        return;
                    }
                }
            }

            EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                    new ParsingEmbeddedDocumentExtractor(context));
            if (!extractor.shouldParseEmbedded(metadata)) {
                return;
            }

            if (!wcontext.isMainDB() && !wcontext.isBackup()) {
                // if this is a "backup" but its main db was not found
                logger.info("Creating separate report for {}", DB.getPath()); //$NON-NLS-1$
            }

            // create report for main dbs and backups which main db was not found
            createReport(dbChatList, searcher, contacts, handler, extractor, account, tmpDB, context);

            // and free memory used by main dbs and backups which main db was not found
            dbsFound.remove(DB.getId());

        } catch (Exception e) {
            if (e instanceof TikaException)
                throw (TikaException) e;
            else
                throw new TikaException("WAExtractorException Exception", e); //$NON-NLS-1$
        } finally {
            if (dbsFound.size() == backupsMerged.get() + dbsSearchedForAndAdded) {
                // just merged backups left in map, clear all remaining heavy data
                logger.info("Clearing remaining whatsapp decoded data from cache.");
                dbsFound.values().stream().filter(wacontext -> wacontext.getChalist() != null)
                        .forEach(wacontext -> wacontext.getChalist().clear());
            }
        }

    }

    private void parseWhatsAppAccount(InputStream is, ParseContext context, ContentHandler handler, boolean isAndroid)
            throws SAXException, IOException, TikaException {
        WAAccount account = null;
        if (isAndroid)
            account = WAAccount.getFromAndroidXml(is);
        else
            account = WAAccount.getFromIOSPlist(is);

        if (account == null) {
            throw new TikaException("Corrupted WA account file.");
        }

        Metadata meta = new Metadata();
        meta.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_ACCOUNT.toString());
        meta.set(TikaCoreProperties.TITLE, account.getTitle());
        meta.set(ExtraProperties.USER_NAME, account.getName());
        meta.set(ExtraProperties.USER_PHONE, getInternationalPhone(account.getId()));
        meta.set(ExtraProperties.USER_ACCOUNT, account.getFullId());
        meta.set(ExtraProperties.USER_ACCOUNT_TYPE, WHATSAPP);
        meta.set(ExtraProperties.USER_NOTES, account.getStatus());
        meta.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());

        if (account.getAvatar() != null) {
            meta.set(ExtraProperties.THUMBNAIL_BASE64, Base64.getEncoder().encodeToString(account.getAvatar()));
        }

        EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                new ParsingEmbeddedDocumentExtractor(context));
        ReportGenerator reportGenerator = new ReportGenerator();

        if (extractor.shouldParseEmbedded(meta)) {
            ByteArrayInputStream bais = new ByteArrayInputStream(reportGenerator.generateAccountHtml(account));
            extractor.parseEmbedded(bais, handler, meta, false);
        }
    }

    private static final Pattern numbers = Pattern.compile("[0-9]+"); //$NON-NLS-1$

    private String getInternationalPhone(String id) {
        Matcher m = numbers.matcher(id);
        if (m.matches())
            return "+" + id; //$NON-NLS-1$
        else
            return null;
    }

    private WAAccount getUserAccount(IItemSearcher searcher, String dbPath, boolean isAndroid) {
        String query = BasicProps.NAME + ":"; //$NON-NLS-1$
        if (isAndroid)
            query += "\"com.whatsapp_preferences.xml\""; //$NON-NLS-1$
        else
            query += "\"group.net.whatsapp.WhatsApp.shared.plist\""; //$NON-NLS-1$
        if (searcher != null) {
            List<IItemBase> result = searcher.search(query);
            IItemBase item = getBestItem(result, dbPath);
            if (item != null) {
                try (InputStream is = item.getBufferedStream()) {
                    WAAccount account = isAndroid ? WAAccount.getFromAndroidXml(is) : WAAccount.getFromIOSPlist(is);
                    if (account != null)
                        return account;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        WAAccount account = new WAAccount("unknownAccount");
        account.setUnknown(true);
        return account;
    }

    private IItemBase getBestItem(List<IItemBase> result, String path) {
        while ((path = new File(path).getParent()) != null) {
            for (IItemBase item : result) {
                if (item.getPath().startsWith(path)) {
                    return item;
                }
            }
        }
        return null;
    }

    private String formatContact(WAContact contact, Map<String, String> cache) {
        String result = cache.get(contact.getId());
        if (result == null) {
            if (contact.getName() == null) {
                result = contact.getFullId();
            } else if (contact.getName().trim().equals(contact.getId())) {
                result = contact.getFullId();
            } else {
                result = contact.getName().trim() + " (" + contact.getFullId() + ")"; //$NON-NLS-1$ //$NON-NLS-2$
            }
            cache.put(contact.getId(), result);
        }
        return result;
    }

    private void fillGroupRecipients(Metadata meta, Chat c, String from, Map<String, String> cache) {
        for (WAContact member : c.getGroupmembers()) {
            String gmb = formatContact(member, cache);
            if (!gmb.equals(from)) {
                meta.add(org.apache.tika.metadata.Message.MESSAGE_TO, gmb);
            }
        }
    }

    private void extractMessages(String chatName, Chat c, List<Message> messages, WAAccount account,
            WAContactsDirectory contacts, int parentVirtualId, ContentHandler handler,
            EmbeddedDocumentExtractor extractor, Map<String, String> cache) throws SAXException, IOException {
        int msgCount = 0;
        for (dpf.mg.udi.gpinf.whatsappextractor.Message m : messages) {

            Metadata meta = new Metadata();
            meta.set(TikaCoreProperties.TITLE, chatName + "_message_" + msgCount++); //$NON-NLS-1$
            meta.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_MESSAGE.toString());
            meta.set(ExtraProperties.PARENT_VIRTUAL_ID, Integer.toString(parentVirtualId));
            meta.set(ExtraProperties.PARENT_VIEW_POSITION, String.valueOf(m.getId()));
            meta.set(ExtraProperties.USER_ACCOUNT_TYPE, WHATSAPP);
            meta.set(ExtraProperties.MESSAGE_DATE, m.getTimeStamp());
            meta.set(TikaCoreProperties.CREATED, m.getTimeStamp());
            meta.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());

            if (!m.isSystemMessage()) {
                String local = formatContact(account, cache);
                String remote = m.getRemoteResource();
                if (remote != null) {
                    WAContact contact = contacts.getContact(remote);
                    remote = contact == null ? remote : formatContact(contact, cache);
                }
                if (m.isFromMe()) {
                    meta.set(org.apache.tika.metadata.Message.MESSAGE_FROM, local);
                    if (c.isGroupChat()) {
                        fillGroupRecipients(meta, c, local, cache);
                    } else {
                        meta.add(org.apache.tika.metadata.Message.MESSAGE_TO, remote);
                    }
                } else {
                    meta.set(org.apache.tika.metadata.Message.MESSAGE_FROM, remote);
                    if (c.isGroupChat()) {
                        fillGroupRecipients(meta, c, remote, cache);
                    } else {
                        meta.add(org.apache.tika.metadata.Message.MESSAGE_TO, local);
                    }
                }
            }
            meta.set(ExtraProperties.MESSAGE_BODY, m.getData());
            meta.set(ExtraProperties.URL, m.getUrl());

            meta.set("mediaName", m.getMediaName()); //$NON-NLS-1$
            meta.set("mediaMime", m.getMediaMime()); //$NON-NLS-1$
            if (m.getMediaSize() != 0) {
                meta.set("mediaSize", Long.toString(m.getMediaSize()));
            }
            if (m.getMediaQuery() != null) {
                meta.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_ATTACHMENT.toString());
                meta.set(ExtraProperties.LINKED_ITEMS, revertEscapeQuery(m.getMediaQuery())); // $NON-NLS-1$
            }
            if (!m.getChildPornSets().isEmpty()) {
                meta.set("hash:status", "pedo"); //$NON-NLS-1$ //$NON-NLS-2$
                for (String set : m.getChildPornSets()) {
                    meta.add("hash:set", set); //$NON-NLS-1$
                }
            }

            // TODO store thumb in metadata?

            if (m.getMessageType() == MessageType.LOCATION_MESSAGE
                    || m.getMessageType() == MessageType.SHARE_LOCATION_MESSAGE) {
                meta.set(ExtraProperties.LOCATIONS, m.getLatitude() + ";" + m.getLongitude()); //$NON-NLS-1$
            }

            if (m.getMessageStatus() != null) {
                meta.set("messageStatus", m.getMessageStatus().toString()); //$NON-NLS-1$
            }

            if (m.isCall()) {
                meta.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_CALL.toString());
                meta.set("duration", ReportGenerator.formatMMSS(m.getMediaDuration())); //$NON-NLS-1$
            }

            if (meta.get(ExtraProperties.MESSAGE_BODY) == null) {
                meta.set(ExtraProperties.MESSAGE_BODY, MESSAGE_TYPE_PREFIX + m.getMessageType().toString());
            }
            if (m.getMediaCaption() != null) {
                meta.add(ExtraProperties.MESSAGE_BODY, m.getMediaCaption());
            }
            if (m.getVcards() != null && !m.getVcards().isEmpty()) {
                meta.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, VCardParser.VCARD_MIME.toString());
                for (String vcard : m.getVcards()) {
                    extractor.parseEmbedded(new ByteArrayInputStream(vcard.getBytes(StandardCharsets.UTF_8)), handler,
                            meta, false);
                }
            } else {
                meta.set(BasicProps.LENGTH, ""); //$NON-NLS-1$
                extractor.parseEmbedded(new EmptyInputStream(), handler, meta, false);
            }
        }
    }

    private void storeLinkedHashes(List<Message> messages, Metadata metadata, IItemSearcher searcher) {
        for (Message m : messages) {
            if (m.getMediaQuery() != null && m.getMediaSize() > 2) {
                String query = revertEscapeQuery(m.getMediaQuery());
                metadata.add(ExtraProperties.LINKED_ITEMS, query);
                if (m.isFromMe()) {
                    metadata.add(ExtraProperties.SHARED_HASHES, query);
                }
            }
        }
    }

    private String revertEscapeQuery(String query) {
        if (query.startsWith("'") && query.endsWith("'")) {
            query = query.substring(1, query.length() - 1);
        } else if (query.startsWith("&quot;") && query.endsWith("&quot;")) {
            query = SimpleHTMLEncoder.htmlDecode(query).replace("\\\"", "\"");
            query = query.substring(1, query.length() - 1);
        }
        return query;
    }

    private String escapeQuery(String query, boolean isHashQuery) {
        if (isHashQuery) {
            return "'" + query + "'";
        } else {
            return SimpleHTMLEncoder.htmlEncode("\"" + query.replace("\"", "\\\"") + "\"");
        }
    }

    private void storeLocations(List<Message> messages, Metadata metadata) {
        for (Message m : messages) {
            if (m.getMessageType() == MessageType.LOCATION_MESSAGE
                    || m.getMessageType() == MessageType.SHARE_LOCATION_MESSAGE) {
                if (m.getLatitude() != 0.0 && m.getLongitude() != 0.0) {
                    metadata.add(ExtraProperties.LOCATIONS, m.getLatitude() + ";" + m.getLongitude()); //$NON-NLS-1$
                }
            }
        }
    }

    private void getAvatar(IItemSearcher searcher, WAContact contact) {
        if (searcher != null && contact.getAvatar() == null) {
            List<IItemBase> result = searcher
                    .search(BasicProps.NAME + ":\"" + escape(searcher, contact.getFullId()) + ".j\""); //$NON-NLS-1$ //$NON-NLS-2$
            if (result.isEmpty()) {
                if (contact.getAvatarPath() != null) {
                    String avatarFileBase = contact.getAvatarPath();
                    if (avatarFileBase.contains("/")) { //$NON-NLS-1$
                        avatarFileBase = avatarFileBase.substring(avatarFileBase.lastIndexOf('/') + 1); // $NON-NLS-1$
                    }
                    avatarFileBase = escape(searcher, avatarFileBase);
                    // Try file .jpg
                    result = searcher.search(BasicProps.NAME + ":\"" + avatarFileBase + ".jpg\""); //$NON-NLS-1$ //$NON-NLS-2$
                    if (result.isEmpty()) {
                        // Try file .thumb
                        result = searcher.search(BasicProps.NAME + ":\"" + avatarFileBase + ".thumb\""); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                }
            }
            if (result.isEmpty()) {
                if (contact.getId() != null && !contact.getId().isEmpty()) {
                    result = searcher
                            .search(BasicProps.NAME + ":(" + escape(searcher, contact.getId()) + " AND (jpg thumb))"); //$NON-NLS-1$ //$NON-NLS-2$
                    result = filterAvatars(result, contact.getId());
                    Collections.sort(result, new AvatarComparator());
                }
            }

            if (!result.isEmpty()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];

                try (InputStream is = result.get(0).getBufferedStream()) {
                    int len = 0;
                    while ((len = is.read(buf)) != -1)
                        bos.write(buf, 0, len);
                    contact.setAvatar(bos.toByteArray());

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private String escape(IItemSearcher searcher, String string) {
        if (searcher != null)
            return searcher.escapeQuery(string);
        else
            return string;
    }

    private List<IItemBase> filterAvatars(List<IItemBase> avatars, String id) {
        // WhatsApp initial release 2009-01-01
        long startTime = 1230768000;
        long endTime = System.currentTimeMillis() / 1000;
        ArrayList<IItemBase> result = new ArrayList<IItemBase>();
        for (IItemBase item : avatars) {
            // filter group avatars and unrelated images
            if (item.getName().startsWith(id) && item.getName().split("-").length < 3) { //$NON-NLS-1$
                String str = item.getName().substring(id.length());
                int idx = str.indexOf("."); //$NON-NLS-1$
                if (str.startsWith("-") && idx > 0) { //$NON-NLS-1$
                    String t = str.substring(1, idx); // $NON-NLS-1$
                    try {
                        Long time = Long.valueOf(t);
                        if (time > startTime && time < endTime) {
                            result.add(item);
                        }
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                } else if (str.equals(".thumb") || str.equals(".jpg")) { //$NON-NLS-1$ //$NON-NLS-2$
                    result.add(item);
                }
            }
        }
        return result;
    }

    // sort newer avatar to be first
    private class AvatarComparator implements Comparator<IItemBase> {
        @Override
        public int compare(IItemBase o1, IItemBase o2) {
            return o2.getName().compareTo(o1.getName());
        }
    }

    private void parseWhatsAppContacts(InputStream stream, ContentHandler handler, Metadata metadata,
            ParseContext context, ExtractorFactory extFactory) throws IOException, SAXException, TikaException {

        extFactory.setConnectionParams(stream, metadata, context, this);
        EmbeddedDocumentExtractor extractor = context.get(EmbeddedDocumentExtractor.class,
                new ParsingEmbeddedDocumentExtractor(context));
        IItemSearcher searcher = context.get(IItemSearcher.class);
        TemporaryResources tmp = new TemporaryResources();

        if (extractor.shouldParseEmbedded(metadata)) {
            TikaInputStream tis = TikaInputStream.get(stream, tmp);
            try {
                WAContactsExtractor waExtractor = extFactory.createContactsExtractor(tis.getFile());
                waExtractor.extractContactList();

                ItemInfo itemInfo = context.get(ItemInfo.class);
                String path = null;
                if (itemInfo != null) {
                    path = itemInfo.getPath();
                }
                WAContactsDirectory contacts = getWAContactsDirectoryForPath(path, null, null);
                contacts.putAll(waExtractor.getContactsDirectory());

                String dbPath = ((ItemInfo) context.get(ItemInfo.class)).getPath();
                WAAccount account = getUserAccount(searcher, dbPath, extFactory instanceof ExtractorAndroidFactory);

                ReportGenerator reportGenerator = new ReportGenerator();
                for (WAContact c : waExtractor.getContactsDirectory().contacts()) {
                    Metadata cMetadata = new Metadata();
                    cMetadata.set(IndexerDefaultParser.INDEXER_CONTENT_TYPE, WHATSAPP_CONTACT.toString());
                    cMetadata.set(TikaCoreProperties.TITLE, c.getTitle());
                    cMetadata.set(ExtraProperties.USER_NAME, c.getName());
                    cMetadata.set(ExtraProperties.USER_PHONE, getInternationalPhone(c.getId()));
                    cMetadata.set(ExtraProperties.USER_ACCOUNT, c.getFullId());
                    cMetadata.set(ExtraProperties.USER_ACCOUNT_TYPE, WHATSAPP);
                    cMetadata.set(ExtraProperties.CONTACT_OF_ACCOUNT, account.getFullId());
                    cMetadata.set(ExtraProperties.USER_NOTES, c.getStatus());
                    cMetadata.set(ExtraProperties.DECODED_DATA, Boolean.TRUE.toString());

                    getAvatar(searcher, c);
                    if (c.getAvatar() != null) {
                        cMetadata.set(ExtraProperties.THUMBNAIL_BASE64, Base64.getEncoder().encodeToString(c.getAvatar()));
                    }

                    if (extractor.shouldParseEmbedded(cMetadata)) {
                        ByteArrayInputStream chatStream = new ByteArrayInputStream(
                                reportGenerator.genarateContactHtml(c));
                        extractor.parseEmbedded(chatStream, handler, cMetadata, false);
                    }
                }

            } catch (Exception e) {
                sqliteParser.parse(tis, handler, metadata, context);
                throw new TikaException("WAExtractorException Exception", e); //$NON-NLS-1$

            } finally {
                tmp.dispose();
            }
        }
    }

    private WAContactsDirectory getWAContactsDirectoryForPath(String path, IItemSearcher searcher,
            Class<?> extFactoryClass)
            throws IOException, WAExtractorException, InstantiationException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
        if (path == null) {
            path = ""; //$NON-NLS-1$
        } else if (path.contains("/")) { //$NON-NLS-1$
            path = path.substring(0, path.lastIndexOf('/')); // $NON-NLS-1$
        } else if (path.contains("\\")) { //$NON-NLS-1$
            path = path.substring(0, path.lastIndexOf('\\')); // $NON-NLS-1$
        }

        WAContactsDirectory cd = contactsDirectoriesMap.get(path);
        if (cd == null) {
            cd = getContacts(path, searcher, extFactoryClass);
            contactsDirectoriesMap.put(path, cd);
        }
        return cd;
    }

    private WAContactsDirectory getContacts(String path, IItemSearcher searcher, Class<?> extFactoryClass)
            throws IOException, WAExtractorException, InstantiationException, IllegalAccessException,
            IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
        if (searcher == null) {
            return new WAContactsDirectory();
        }
        String query = BasicProps.PATH + ":\"" + searcher.escapeQuery(path) + "\""; //$NON-NLS-1$ //$NON-NLS-2$
        query += " && " + BasicProps.CONTENTTYPE + ":(\"" + WA_DB.toString() + "\" || \"" + CONTACTS_V2.toString() //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                + "\")"; //$NON-NLS-1$
        List<IItemBase> items = searcher.search(query);
        if (items.size() == 0) {
            return new WAContactsDirectory();
        }
        IItemBase item = items.get(0);
        ParseContext context = new ParseContext();
        context.set(IItemSearcher.class, searcher);
        context.set(IItemBase.class, item);
        ExtractorFactory extFactory = (ExtractorFactory) extFactoryClass.newInstance();

        try (InputStream is = item.getBufferedStream()) {
            extFactory.setConnectionParams(is, null, context, this);
            WAContactsExtractor waExtractor = extFactory.createContactsExtractor(item.getTempFile());
            waExtractor.extractContactList();
            return waExtractor.getContactsDirectory();
        }
    }

    private static abstract class ExtractorFactory {

        InputStream is;
        Metadata metadata;
        ParseContext context;
        WhatsAppParser connFactory;

        abstract Extractor createMessageExtractor(File file, WAContactsDirectory directory, WAAccount account);

        abstract WAContactsExtractor createContactsExtractor(File file);

        void setConnectionParams(InputStream is, Metadata metadata, ParseContext context, WhatsAppParser connFactory) {
            this.is = is;
            this.metadata = metadata;
            this.context = context;
            this.connFactory = connFactory;
        }

        protected Connection getConnection() throws SQLException {
            try {
                return connFactory.getConnection(is, metadata, context);
            } catch (IOException e) {
                throw new SQLException(e);
            }
        }
    }

    // must be static and non be private because of newInstance in getContacts()
    // method
    protected static class ExtractorAndroidFactory extends ExtractorFactory {

        @Override
        public Extractor createMessageExtractor(File file, WAContactsDirectory directory, WAAccount account) {
            return new ExtractorAndroid(file, directory, account) {
                @Override
                protected Connection getConnection() throws SQLException {
                    return ExtractorAndroidFactory.this.getConnection();
                }
            };
        }

        @Override
        public WAContactsExtractor createContactsExtractor(File file) {
            return new WAContactsExtractorAndroid(file, new WAContactsDirectory()) {
                @Override
                protected Connection getConnection() throws SQLException {
                    return ExtractorAndroidFactory.this.getConnection();
                }
            };
        }

    }

    // must be static and non be private because of newInstance in getContacts()
    // method
    protected static class ExtractorIOSFactory extends ExtractorFactory {

        @Override
        public Extractor createMessageExtractor(File file, WAContactsDirectory directory, WAAccount account) {
            return new ExtractorIOS(file, directory, account) {
                @Override
                protected Connection getConnection() throws SQLException {
                    return ExtractorIOSFactory.this.getConnection();
                }
            };
        }

        @Override
        public WAContactsExtractor createContactsExtractor(File file) {
            return new WAContactsExtractorIOS(file, new WAContactsDirectory()) {
                @Override
                protected Connection getConnection() throws SQLException {
                    return ExtractorIOSFactory.this.getConnection();
                }
            };
        }

    }

    private List<Future<?>> searchMediaFilesForMessagesInBatches(List<Message> messages, IItemSearcher searcher,
            ContentHandler handler, EmbeddedDocumentExtractor extractor, File dbPath, ParseContext context,
            AtomicInteger downloadedFiles) {

        if (searcher == null) {
            return Collections.emptyList();
        }
        List<List<Message>> listsToProcess = new ArrayList<>();
        List<Message> messagesToProcess = new ArrayList<>();
        int count = 0;
        for (Message m : messages) {
            if ((m.getMediaHash() != null && !m.getMediaHash().isEmpty())
                    || (m.getMediaName() != null && !m.getMediaName().isEmpty())) {
                messagesToProcess.add(m);
                count++;
                if (count == MESSAGE_SEARCH_BATCH_SIZE) {
                    count = 0;
                    listsToProcess.add(messagesToProcess);
                    messagesToProcess = new ArrayList<>();
                }
            }
        }
        if (count > 0) {
            listsToProcess.add(messagesToProcess);
        }

        ArrayList<Future<?>> futures = new ArrayList<>();
        for (List<Message> listToProcess : listsToProcess) {
            futures.addAll(searchMediaFilesForMessages(listToProcess, searcher, handler, extractor, dbPath, context,
                    downloadedFiles));
        }
        return futures;
    }

    private void setItemToMessage(IItemBase item, List<Message> messageList, String query, boolean isHashQuery, boolean saveItemRef) {
        if (messageList != null && saveItemRef) {
            for (Message m : messageList) {
                m.setMediaItem(item);
                m.setMediaQuery(escapeQuery(query, isHashQuery));
            }
        }
    }

    private List<Future<?>> searchMediaFilesForMessages(List<Message> messages, IItemSearcher searcher,
            ContentHandler handler, EmbeddedDocumentExtractor extractor, File dbPath, ParseContext context,
            AtomicInteger downloadedFiles) {
        
        // just save heavy item refs if creating report, per chat, not per database
        boolean saveItemRef = downloadedFiles == null;

        Map<String, List<Message>> hashesToSearchFor = new HashMap<>();
        Map<Pair<String, Long>, List<Message>> fileNameAndSizeToSearchFor = new HashMap<>();
        // First search for hashes
        for (Message m : messages) {
            if (m.getMediaItem() != null) {
                continue;
            }
            String hash = m.getMediaHash();
            if (hash != null && !hash.isEmpty()) {
                List<Message> messageList = hashesToSearchFor.get(hash);
                if (messageList == null) {
                    messageList = new ArrayList<>();
                    hashesToSearchFor.put(hash, messageList);
                }
                messageList.add(m);
            } else {
                String fileName = m.getMediaName();
                long fileSize = m.getMediaSize();
                if (fileName != null && !fileName.isEmpty() && fileSize > 0) {
                    if (fileName.contains("/")) { //$NON-NLS-1$
                        fileName = fileName.substring(fileName.lastIndexOf('/') + 1); // $NON-NLS-1$
                    }
                    Pair<String, Long> key = Pair.of(fileName, fileSize);
                    List<Message> messageList = fileNameAndSizeToSearchFor.get(key);
                    if (messageList == null) {
                        messageList = new ArrayList<>();
                        fileNameAndSizeToSearchFor.put(key, messageList);
                    }
                    messageList.add(m);
                }
            }
        }

        if (!hashesToSearchFor.isEmpty()) {
            StringBuilder allHashesQueryBuilder = new StringBuilder();
            allHashesQueryBuilder.append("sha-256:("); //$NON-NLS-1$
            for (String h : hashesToSearchFor.keySet()) {
                allHashesQueryBuilder.append(h).append(" "); //$NON-NLS-1$
            }
            allHashesQueryBuilder.append(")"); //$NON-NLS-1$
            String allHashesQuery = allHashesQueryBuilder.toString();
            List<IItemBase> result = dpf.sp.gpinf.indexer.parsers.util.Util.getItems(allHashesQuery, searcher);
            for (IItemBase item : result) {
                String hash = (String) item.getExtraAttribute("sha-256"); //$NON-NLS-1$
                List<Message> messageList = hashesToSearchFor.remove(hash);

                setItemToMessage(item, messageList, "sha-256:" + hash, true, saveItemRef);

            }
        }

        // for media messages without hash, try to find by filename and size
        if (!fileNameAndSizeToSearchFor.isEmpty()) {
            StringBuilder fileNameAndSizeQueryBuilder = new StringBuilder();
            for (Pair<String, Long> key : fileNameAndSizeToSearchFor.keySet()) {
                fileNameAndSizeQueryBuilder.append("("); //$NON-NLS-1$
                fileNameAndSizeQueryBuilder.append(BasicProps.NAME).append(":\""); //$NON-NLS-1$
                fileNameAndSizeQueryBuilder.append(searcher.escapeQuery(key.getLeft()));
                fileNameAndSizeQueryBuilder.append("\" AND "); //$NON-NLS-1$
                fileNameAndSizeQueryBuilder.append(BasicProps.LENGTH).append(":"); //$NON-NLS-1$
                fileNameAndSizeQueryBuilder.append(key.getRight().toString());
                fileNameAndSizeQueryBuilder.append(") "); //$NON-NLS-1$

            }

            String fileNameAndSizeQuery = fileNameAndSizeQueryBuilder.toString();
            List<IItemBase> result = dpf.sp.gpinf.indexer.parsers.util.Util.getItems(fileNameAndSizeQuery, searcher);
            for (IItemBase item : result) {
                if (item.getName() != null && !item.getName().isEmpty() && item.getLength() != null
                        && item.getLength() > 0) {
                    String fileName = item.getName();
                    long fileSize = item.getLength();
                    if (fileName.contains("/")) { //$NON-NLS-1$
                        fileName = fileName.substring(fileName.lastIndexOf('/') + 1); // $NON-NLS-1$
                    }
                    Pair<String, Long> key = Pair.of(fileName, fileSize);
                    List<Message> messageList = fileNameAndSizeToSearchFor.get(key);
                    String query = BasicProps.NAME + ":\"" + searcher.escapeQuery(fileName) + "\" AND " //$NON-NLS-1$ //$NON-NLS-2$
                            + BasicProps.LENGTH + ":" + fileSize; 
                    setItemToMessage(item, messageList, query, false, saveItemRef);
                }
            }
        }

        // fallback search for media items that have hash in database, but hashes were
        // not found
        // It is possible that the the file has been padded with zeros
        // see https://github.com/sepinf-inc/IPED/issues/486
        // try to to find by name and by approximate size, then check if it ends with
        // zeros
        if (linkMediasByNameAndApproxSizeFallback) {
            if (!hashesToSearchFor.isEmpty()) {
                Map<String, List<Message>> fallBackFileNamesToSearchFor = new HashMap<>();
                for (List<Message> messageList : hashesToSearchFor.values()) {
                    for (Message m : messageList) {
                        String fileName = m.getMediaName();
                        if (fileName != null && !fileName.isEmpty()) {
                            if (fileName.contains("/")) { //$NON-NLS-1$
                                fileName = fileName.substring(fileName.lastIndexOf('/') + 1); // $NON-NLS-1$
                            }
                            List<Message> newMessageList = fallBackFileNamesToSearchFor.get(fileName);
                            if (newMessageList == null) {
                                newMessageList = new ArrayList<>();
                                fallBackFileNamesToSearchFor.put(fileName, newMessageList);
                            }
                            newMessageList.add(m);
                        }
                    }
                }

                if (!fallBackFileNamesToSearchFor.isEmpty()) {
                    StringBuilder fallBackQueryBuilder = new StringBuilder();
                    fallBackQueryBuilder.append(BasicProps.NAME).append(":("); //$NON-NLS-1$
                    for (String fileName : fallBackFileNamesToSearchFor.keySet()) {
                        fileName = searcher.escapeQuery(fileName);
                        fallBackQueryBuilder.append("\"").append(fileName).append("\" "); //$NON-NLS-1$ //$NON-NLS-2$
                    }
                    fallBackQueryBuilder.append(")"); //$NON-NLS-1$

                    String fallBackQuery = fallBackQueryBuilder.toString();
                    List<IItemBase> result = dpf.sp.gpinf.indexer.parsers.util.Util.getItems(fallBackQuery, searcher);
                    for (IItemBase item : result) {
                        if (item.getName() != null && item.getLength() != null && item.getLength() > 0) {
                            String fileName = item.getName();
                            if (fileName.contains("/")) { //$NON-NLS-1$
                                fileName = fileName.substring(fileName.lastIndexOf('/') + 1); // $NON-NLS-1$
                            }
                            List<Message> messageList = fallBackFileNamesToSearchFor.get(fileName);
                            if (messageList != null) {
                                messageList = messageList.stream().filter(m -> {
                                    long mediaSize = m.getMediaSize();
                                    long fileSize = item.getLength();
                                    return (fileSize >= mediaSize + 1 && fileSize <= mediaSize + 15
                                            && itemStreamEndsWithZeros(item, mediaSize));
                                }).collect(Collectors.toList());

                                setItemToMessage(item, messageList, BasicProps.HASH + ":" + item.getHash(), true, saveItemRef);
                            }
                        }
                    }
                }
            }
        }
        // if download files from the internet is allowed
        ArrayList<Future<?>> futures = new ArrayList<>();
        if (isDownloadMediaFilesEnabled() && downloadedFiles != null) {
            if (!hashesToSearchFor.isEmpty()) {

                ArrayList<LinkDownloader> links = new ArrayList<>();
                try (LinkExtractor le = new LinkExtractor(dbPath, new HashSet<String>(hashesToSearchFor.keySet()),
                        downloadConnectionTimeout, downloadReadTimeout)) {
                    le.extractLinks();
                    links = le.getLinks();

                } catch (Exception e) {
                    // cannot extract link
                    logger.warn("Could not extract links from database " + dbPath, e);
                    return futures;
                }

                for (LinkDownloader ld : links) {
                    if (ld == null || ld.getHash() == null || !hashesDownloaded.add(ld.getHash())) {
                        continue;
                    }
                    Runnable r = new Runnable() {

                        @Override
                        public void run() {

                            try (TemporaryResources tmp = new TemporaryResources()) {

                                File f = tmp.createTemporaryFile(), fout = tmp.createTemporaryFile();

                                ld.downloadUsingStream(f);

                                ld.decript(f, fout);

                                Metadata downloadMetadata = new Metadata();

                                downloadMetadata.set(ExtraProperties.DOWNLOADED_DATA, "true");
                                downloadMetadata.set(TikaCoreProperties.TITLE,
                                        "Dowloaded_item_" + downloadedFiles.incrementAndGet());

                                try (FileInputStream out = new FileInputStream(fout)) {
                                    synchronized (extractor) {
                                        extractor.parseEmbedded(out, handler, downloadMetadata, false);
                                    }
                                }

                            } catch (URLnotFound ex) {
                                // do not log this error as it is expected

                            } catch (Exception e) {
                                logger.warn("Error trying to download medias referenced by " + dbPath, e);
                            }

                        }
                    };

                    futures.add(getExecutor().submit(r));
                }

            }
        }
        return futures;
    }

    /**
     * Check it the media file is padded with zeros (check if all bytes beyond
     * mediaSize are zeros)
     * 
     * @param item
     * @param mediaSize
     * @return
     */
    private boolean itemStreamEndsWithZeros(IItemBase item, long mediaSize) {
        try (SeekableInputStream sis = item.getSeekableInputStream()) {
            sis.seek(mediaSize);
            byte[] bytes = new byte[15];
            int read = org.apache.commons.io.IOUtils.read(sis, bytes);
            // this loop will run at most 15 times
            while (--read >= 0) {
                if (bytes[read] != 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException ex) {
            return false;
        }
    }
}
