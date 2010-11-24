package org.albite.book.model.book;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.io.Connection;
import javax.microedition.io.Connector;
import javax.microedition.io.InputConnection;
import javax.microedition.io.file.FileConnection;
import javax.microedition.lcdui.Form;
import javax.microedition.lcdui.StringItem;
import org.albite.book.model.parser.HTMLTextParser;
import org.albite.book.model.parser.PlainTextParser;
import org.albite.book.model.parser.TextParser;
import org.albite.io.PartitionedConnection;
import org.albite.io.RandomReadingFile;
import org.albite.util.archive.zip.ArchiveZip;
import org.geometerplus.zlibrary.text.hyphenation.Languages;
import org.kxml2.io.KXmlParser;
import org.kxml2.kdom.Document;
import org.kxml2.kdom.Element;
import org.kxml2.kdom.Node;
import org.xmlpull.v1.XmlPullParserException;

public abstract class Book
        implements Connection {

    public static final String EPUB_EXTENSION = ".epub";
    public static final String PLAIN_TEXT_EXTENSION = ".txt";
    public static final String HTM_EXTENSION = ".htm";
    public static final String HTML_EXTENSION = ".html";
    public static final String XHTML_EXTENSION = ".xhtml";

    public static final String[] SUPPORTED_BOOK_EXTENSIONS = new String[] {
        EPUB_EXTENSION,
        PLAIN_TEXT_EXTENSION,
        HTM_EXTENSION, HTML_EXTENSION, XHTML_EXTENSION
    };

    protected static final String   USERDATA_BOOKMARK_TAG    = "b";
    protected static final byte[]   USERDATA_BOOKMARK_TAG_BYTES =
            USERDATA_BOOKMARK_TAG.getBytes();

    protected static final String   USERDATA_CHAPTER_ATTRIB  = "c";
    protected static final byte[]   USERDATA_CHAPTER_ATTRIB_BYTES =
            USERDATA_CHAPTER_ATTRIB.getBytes();

    protected static final String   USERDATA_ENCODING_ATTRIB = "e";
    protected static final byte[]   USERDATA_ENCODING_ATTRIB_BYTES =
            USERDATA_ENCODING_ATTRIB.getBytes();

    protected static final String   USERDATA_POSITION_ATTRIB = "p";
    protected static final byte[]   USERDATA_POSITION_ATTRIB_BYTES =
            USERDATA_POSITION_ATTRIB.getBytes();

    private static final int ALBX_MAGIC_NUMBER = 0x616C6278;

    /*
     * Main info
     */
    protected String                title                    = "Untitled";
    protected String                author                   = "Unknown Author";
    protected String                language                 = null;
    protected String                currentLanguage          = null;
    protected String                description              = null;

    /*
     * Contains various book attribs,
     * e.g. 'fiction', 'for_children', 'prose', etc.
     */
    protected BookmarkManager       bookmarks = new BookmarkManager();

    /*
     * .alx book user settings
     */
    protected FileConnection        bookSettingsFile         = null;
    protected FileConnection        bookmarksFile            = null;
    protected String                bookURL                  = null;

    /*
     * Chapters
     */
    protected Chapter[]             chapters;
    protected Chapter               currentChapter;

    protected TextParser            parser;

    public abstract void close() throws IOException;

    protected void closeUserFiles() throws IOException {
        if (bookSettingsFile != null) {
            bookSettingsFile.close();
        }

        if (bookmarksFile != null) {
            bookmarksFile.close();
        }
    }

    public final String getLanguage() {
        return currentLanguage;
    }

    /**
     * Returns book's original language using the its full name, rather than
     * just the language code. If such is not found, the code will be
     * returned anyway.
     *
     * @return full language name or its code if there is no full name alias
     * for the current language code
     */
    public final String getLanguageAlias() {
        final String[][] langs = Languages.LANGUAGES;
        for (int i = 0; i < langs.length; i++) {
            /*
             * Using reference comparison, as the language strings
             * are expected to have been already interned
             */
            if (langs[i][0].equalsIgnoreCase(language)) {
                return langs[i][1];
            }
        }

        return language;
    }

    /**
     *
     * @param language
     * @return true, if the language was changed
     */
    public final boolean setLanguage(final String language) {
        if (language == null || !language.equalsIgnoreCase(currentLanguage)) {
            currentLanguage = language;
            return true;
        }

        return false;
    }

    public final String getDefaultLanguage() {
        return language;
    }

    /**
     * unload all chapters from memory
     */
    public final void unloadChaptersBuffers() {
        Chapter chap = chapters[0];
        while (chap != null) {
            chap.unload();
            chap = chap.getNextChapter();
        }
    }

    protected void loadUserFiles(final String filename)
            throws BookException, IOException {
        /*
         * Set default chapter
         */
        currentChapter = chapters[0];

        bookSettingsFile = loadUserFile(
                RandomReadingFile.changeExtension(filename, ".alx"));
        bookmarksFile = loadUserFile(
                RandomReadingFile.changeExtension(filename, ".alb"));

        loadUserData();
    }

    protected FileConnection loadUserFile(final String filename)
            throws IOException {

        try {
            System.out.println("Opening [" + filename + "]");
            final FileConnection file = (FileConnection) Connector.open(
                    filename, Connector.READ_WRITE);
            System.out.println("opened!");
            System.out.println(file == null);
            if (file != null && !file.isDirectory()) {

                /*
                 * if there is a dir by that name,
                 * the functionality will be disabled
                 *
                 */
                if (!file.exists()) {

                    /*
                     * create the file if it doesn't exist
                     */
                    file.create();
                }
            }

            return file;
        } catch (SecurityException e) {
        } catch (IOException e) {}
        
        return null;
    }

    private void loadUserData() {
        try {
            loadBookSettings();
        } catch (IOException e) {
        } catch (SecurityException e) {
        } catch (BookException e) {}

        try {
            loadBookmarks();
        } catch (IOException e) {
        } catch (SecurityException e) {
        } catch (BookException e) {}
    }

    private void loadBookSettings() throws IOException, BookException {
        
        if (bookSettingsFile != null) {
            final DataInputStream in = bookSettingsFile.openDataInputStream();
            try {
                if (in.readInt() != ALBX_MAGIC_NUMBER) {
                    throw new BookException("Wrong magic number");
                }
                currentLanguage = in.readUTF();
                currentChapter = getChapter(in.readShort());

                Chapter chapter;
                final int chaptersNumber = in.readShort();
                for (int i = 0; i < chaptersNumber; i++) {
                    chapter = getChapter(i);
                    chapter.setCurrentPosition(in.readInt());
                    chapter.setEncoding(in.readUTF());
                }
            } finally {
                in.close();
            }
        }
    }

    private void loadBookmarks() throws IOException, BookException {

        if (bookmarksFile == null) {
            return;
        }

        /*
         * Loading bookmarks
         */
        InputStream in = bookmarksFile.openInputStream();

        KXmlParser parser = null;
        Document doc = null;
        Element root;
        Element kid;

        try {
            parser = new KXmlParser();
            parser.setInput(new InputStreamReader(in, "UTF-8"));

            doc = new Document();
            doc.parse(parser);
            parser = null;
        } catch (XmlPullParserException e) {
            parser = null;
            doc = null;
            throw new BookException("Wrong XML data.");
        }

        try {

            /*
             * root element
             */
            root = doc.getRootElement();

            int childCount = root.getChildCount();

            for (int i = 0; i < childCount ; i++ ) {
                if (root.getType(i) != Node.ELEMENT) {
                    continue;
                }

                kid = root.getElement(i);

                final int chapter =
                        readIntFromXML(kid, USERDATA_CHAPTER_ATTRIB);

                int position =
                        readIntFromXML(kid, USERDATA_POSITION_ATTRIB);

                if (position < 0) {
                    position = 0;
                }

                if (kid.getName().equals(USERDATA_BOOKMARK_TAG)) {

                    String text = kid.getText(0);

                    if (text == null) {
                        text = "Untitled";
                    }

                    bookmarks.addBookmark(
                            new Bookmark(getChapter(chapter),
                            position, text));
                }
            }

        } catch (NullPointerException e) {
            bookmarks.deleteAll();
            throw new BookException("Missing info (NP Exception)");

        } catch (IllegalArgumentException e) {
            bookmarks.deleteAll();
            throw new BookException("Malformed int data");

        } catch (RuntimeException e) {

            /*
             * document has not got a root element
             */
            bookmarks.deleteAll();
            throw new BookException("Wrong data");

        } finally {
            in.close();
        }
    }
    
    private int readIntFromXML(final Element kid, final String elementName) {
        int number = 0;

        try {
            number = Integer.parseInt(
                kid.getAttributeValue(
                KXmlParser.NO_NAMESPACE, elementName));
        } catch (NumberFormatException nfe) {}

        return number;
    }

    public final void saveBookSettings() {
        if (chapters != null && bookSettingsFile != null) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
                DataOutputStream out = new DataOutputStream(baos);

                try {
                    out.writeInt(ALBX_MAGIC_NUMBER);
                    out.writeUTF(currentLanguage);
                    out.writeShort((short) currentChapter.getNumber());

                    final int chaptersLength = chapters.length;
                    Chapter chapter;

                    out.writeShort((short) chaptersLength);
                    for (int i = 0; i < chaptersLength; i++) {
                        chapter = chapters[i];
                        out.writeInt(chapter.getCurrentPosition());
                        out.writeUTF(chapter.getEncoding());
                    }
                    
                    writeData(baos.toByteArray(), bookSettingsFile);
                } finally {
                    out.close();
                }
            } catch (IOException e) {
            } catch (SecurityException e) {}
        }
    }

    public final void saveBookmarks() {
        if (chapters != null && //i.e. if any chapters have been read
            bookmarksFile != null    //i.e. the file is OK for writing
            ) {

            final byte lt = (byte) ('<'  & 0xFF);
            final byte gt = (byte) ('>'  & 0xFF);
            final byte sl = (byte) ('/'  & 0xFF);
            final byte sp = (byte) (' '  & 0xFF);
            final byte nl = (byte) ('\n' & 0xFF);
            final byte eq = (byte) ('='  & 0xFF);
            final byte qt = (byte) ('"'  & 0xFF);

            final String encoding = "UTF-8";

            byte[] contents = null;

            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
                DataOutputStream out = new DataOutputStream(baos);
                try {
                    /*
                     * Root element
                     */
                    out.write(lt);
                    out.write(USERDATA_BOOKMARK_TAG_BYTES);
                    out.write(gt);
                    out.write(nl);

                    /*
                     * bookmarks
                     * <b c="3" p="1234">Text</bookmark>
                     */
                    Bookmark bookmark = bookmarks.getFirst();

                    while (bookmark != null) {
                        out.write(lt);
                        out.write(USERDATA_BOOKMARK_TAG.getBytes(encoding));
                        out.write(sp);
                        out.write(USERDATA_CHAPTER_ATTRIB.getBytes(encoding));
                        out.write(eq);
                        out.write(qt);
                        out.write(Integer.toString(
                                bookmark.getChapter().getNumber()
                                ).getBytes(encoding));
                        out.write(qt);
                        out.write(sp);
                        out.write(USERDATA_POSITION_ATTRIB
                                .getBytes(encoding));
                        out.write(eq);
                        out.write(qt);
                        out.write(Integer.toString(bookmark.getPosition())
                                .getBytes(encoding));
                        out.write(qt);
                        out.write(gt);
                        out.write(bookmark.getTextForHTML()
                                .getBytes(encoding));
                        out.write(lt);
                        out.write(sl);
                        out.write(USERDATA_BOOKMARK_TAG
                                .getBytes(encoding));
                        out.write(gt);
                        out.write(nl);

                        bookmark = bookmark.next;
                    }

                    /*
                     * Close book tag
                     */
                    out.write(lt);
                    out.write(sl);
                    out.write(USERDATA_BOOKMARK_TAG_BYTES);
                    out.write(gt);
                    out.write(nl);
                    writeData(baos.toByteArray(), bookmarksFile);
                } catch (IOException ioe) {
                } finally {
                    out.close();
                }
            } catch (IOException ioe) {}
        }
    }

    private void writeData(byte[] data, FileConnection file) {
        try {
            file.truncate(0);
            DataOutputStream out = file.openDataOutputStream();
            try {
                out.write(data);
            } catch (IOException e) {
            } finally {
                out.close();
            }
        } catch (IOException e) {
        } catch (SecurityException e) {}
    }

    public final int getChaptersCount() {
        return chapters.length;
    }

    public final Chapter getChapter(final int number) {

        if (number < 0) {
            return chapters[0];
        }

        if (number > chapters.length - 1) {
            return chapters[chapters.length - 1];
        }

        return chapters[number];
    }

    public final void fillBookInfo(Form f) {

        StringItem s;

        s = new StringItem("Title:", title);
        s.setLayout(StringItem.LAYOUT_LEFT);

        f.append(s);

        s = new StringItem("Author:", author);
        f.append(s);

        s = new StringItem("Language:", getLanguageAlias());
        f.append(s);

        if (description != null) {
            f.append(description);
        }

        Hashtable meta = getMeta();

        if (meta != null) {
            for (Enumeration e = getMeta().keys(); e.hasMoreElements();) {
                final String infoName = (String) e.nextElement();
                final String infoValue = (String) meta.get(infoName);

                if (infoName != null && infoValue != null) {
                    s = new StringItem(infoName + ":", infoValue);
                    f.append(s);
                }
            }
        }

        s = new StringItem("Language:", language);
    }

    public abstract Hashtable getMeta();

    public final BookmarkManager getBookmarkManager() {
        return bookmarks;
    }

    public final Chapter getCurrentChapter() {
        return currentChapter;
    }

    public final void setCurrentChapter(final Chapter bc) {
        currentChapter = bc;
    }

    public final int getCurrentChapterPosition() {
        return currentChapter.getCurrentPosition();
    }

    public final void setCurrentChapterPos(final int pos) {
        if (pos < 0 || pos >= currentChapter.getTextBuffer().length) {
            throw new IllegalArgumentException("Position is wrong");
        }

        currentChapter.setCurrentPosition(pos);
    }

    public final TextParser getParser() {
        return parser;
    }

    public static Book open(String filename, final boolean lightMode)
            throws IOException, BookException {

        filename = filename.toLowerCase();

        if (filename.endsWith(EPUB_EXTENSION)) {
            return new EPubBook(filename, lightMode);
        }

        if (filename.endsWith(PLAIN_TEXT_EXTENSION)) {
            return new FileBook(
                    filename, new PlainTextParser(), false, lightMode);
        }

        if (filename.endsWith(HTM_EXTENSION)
                || filename.endsWith(HTML_EXTENSION)
                || filename.endsWith(XHTML_EXTENSION)) {
            return new FileBook(
                    filename, new HTMLTextParser(), true, lightMode);
         }

        throw new BookException("Unsupported file format.");
    }

    protected final void linkChapters() {
        Chapter prev;
        Chapter cur;

        for (int i = 1; i < chapters.length; i++) {
            prev = chapters[i - 1];
            cur  = chapters[i];

            prev.setNextChapter(cur);
            cur.setPrevChapter(prev);
        }
    }

    protected final void splitChapterIntoPieces(
            final InputConnection chapterFile,
            final int chapterFilesize,
            final int maxChapterSize,
            final int chapterNumber,
            final boolean processHtmlEntities,
            final Vector chapters
            ) throws IOException, BookException {

        if (chapterFilesize <= maxChapterSize) {
            chapters.addElement(new Chapter(
                        chapterFile, chapterFilesize,
                        "Chapter #" + (chapterNumber + 1),
                        processHtmlEntities, chapterNumber)
            );

            return;

        } else {

            int kMax = chapterFilesize / maxChapterSize;
            if (chapterFilesize % maxChapterSize > 0) {
                kMax++;
            }

            int left = chapterFilesize;
            int chapSize;

            for (int k = 0; k < kMax; k++) {
                chapSize = (left > maxChapterSize ? maxChapterSize : left);
                chapters.addElement(new Chapter(
                        new PartitionedConnection(
                            chapterFile, k * maxChapterSize, chapSize),
                        chapSize,
                        "Chapter #" + (chapterNumber + k + 1),
                        processHtmlEntities,
                        chapterNumber + k
                        ));
                left -= maxChapterSize;
            }
        }
    }

    /*
     * The maximum file size after which the Filebook is split
     * forcefully into chapters. The split is a dumb one, for it splits
     * on bytes, not characters or tags, i.e. it may split a utf-8 character
     * in two halves, making it unreadable (so that it would be visible as a
     * question mark) or it may split an HTML tag (so that it would become
     * useless and be shown in the text of the chapter)
     */
    protected final int getMaximumTxtFilesize(final boolean lightMode) {
        return (lightMode ? 16 * 1024 : 64 * 1024);
    }

    protected final int getMaximumHtmlFilesize(final boolean lightMode) {
        return (lightMode ? 16 * 1024 : 192 * 1024);
    }

    public abstract int fileSize();

    public final String getURL() {
        return bookURL;
    }

    public abstract ArchiveZip getArchive();
}