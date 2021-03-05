package me.devsaki.hentoid.enums;

import androidx.annotation.Nullable;

import io.objectbox.converter.PropertyConverter;
import me.devsaki.hentoid.R;
import me.devsaki.hentoid.util.network.HttpHelper;
import timber.log.Timber;

/**
 * Created by neko on 20/06/2015.
 * Site enumerator
 */
public enum Site {

    // NOTE : to maintain compatiblity with saved JSON files and prefs, do _not_ edit either existing names or codes
    FAKKU(0, "Fakku", "https://www.fakku.net", R.drawable.ic_menu_fakku), // Legacy support for old fakku archives
    PURURIN(1, "Pururin", "https://pururin.io", R.drawable.ic_menu_pururin),
    HITOMI(2, "hitomi", "https://hitomi.la", R.drawable.ic_menu_hitomi, false, false, false, false), // Hitomi needs a desktop agent to properly display gallery images on some devices
    NHENTAI(3, "nhentai", "https://nhentai.unblock.my.id", R.drawable.ic_menu_nhentai, true, true, false, false),
    TSUMINO(4, "tsumino", "https://www.tsumino.com", R.drawable.ic_menu_tsumino, true, true, false, false),
    HENTAICAFE(5, "hentaicafe", "https://hentai.cafe", R.drawable.ic_menu_hentaicafe, true, true, false, false),
    ASMHENTAI(6, "asmhentai", "https://asmhentai.com", R.drawable.ic_menu_asmhentai, true, true, false, false),
    ASMHENTAI_COMICS(7, "asmhentai comics", "https://comics.asmhentai.com", R.drawable.ic_menu_asmcomics, true, true, false, false),
    EHENTAI(8, "e-hentai", "https://e-hentai.org", R.drawable.ic_menu_ehentai, true, true, false, true),
    FAKKU2(9, "Fakku", "https://www.fakku.net", R.drawable.ic_menu_fakku, true, false, true, false),
    NEXUS(10, "Hentai Nexus", "https://hentainexus.com", R.drawable.ic_menu_nexus),
    MUSES(11, "8Muses", "https://www.8muses.com", R.drawable.ic_menu_8muses),
    DOUJINS(12, "doujins.com", "https://doujins.com/", R.drawable.ic_menu_doujins),
    LUSCIOUS(13, "luscious.net", "https://members.luscious.net/manga/", R.drawable.ic_menu_luscious),
    EXHENTAI(14, "exhentai", "https://exhentai.org", R.drawable.ic_menu_exhentai, true, false, false, true),
    PORNCOMIX(15, "porncomixonline", "https://www.porncomixonline.net/", R.drawable.ic_menu_porncomix),
    HBROWSE(16, "Hbrowse", "https://www.hbrowse.com/", R.drawable.ic_menu_hbrowse),
    HENTAI2READ(17, "Hentai2Read", "https://hentai2read.com/", R.drawable.ic_menu_hentai2read),
    HENTAIFOX(18, "Hentaifox", "https://hentaifox.com", R.drawable.ic_menu_hentaifox),
    MRM(19, "MyReadingManga", "https://myreadingmanga.info/", R.drawable.ic_menu_mrm),
    MANHWA(20, "ManwhaHentai", "https://manhwahentai.me/", R.drawable.ic_menu_manhwa),
    IMHENTAI(21, "Imhentai", "https://imhentai.com", R.drawable.ic_menu_imhentai),
    TOONILY(22, "Toonily", "https://toonily.com/", R.drawable.ic_menu_toonily),
    NONE(98, "none", "", R.drawable.ic_external_library), // External library; fallback site
    PANDA(99, "panda", "https://www.mangapanda.com", R.drawable.ic_menu_panda); // Safe-for-work/wife/gf option; not used anymore and kept here for retrocompatibility


    private final int code;
    private final String description;
    private final String url;
    private final int ico;
    private final boolean useMobileAgent;
    private final boolean useHentoidAgent;
    private final boolean hasImageProcessing;
    private final boolean hasBackupURLs;

    Site(int code,
         String description,
         String url,
         int ico,
         boolean useMobileAgent,
         boolean useHentoidAgent,
         boolean hasImageProcessing,
         boolean hasBackupURLs) {
        this.code = code;
        this.description = description;
        this.url = url;
        this.ico = ico;
        this.useMobileAgent = useMobileAgent;
        this.useHentoidAgent = useHentoidAgent;
        this.hasImageProcessing = hasImageProcessing;
        this.hasBackupURLs = hasBackupURLs;
    }

    Site(int code,
         String description,
         String url,
         int ico) {
        this.code = code;
        this.description = description;
        this.url = url;
        this.ico = ico;
        this.useMobileAgent = true;
        this.useHentoidAgent = false;
        this.hasImageProcessing = false;
        this.hasBackupURLs = false;
    }

    public static Site searchByCode(long code) {
        for (Site s : values())
            if (s.getCode() == code) return s;

        return NONE;
    }

    // Same as ValueOf with a fallback to NONE
    // (vital for forward compatibility)
    public static Site searchByName(String name) {
        for (Site s : values())
            if (s.name().equalsIgnoreCase(name)) return s;

        return NONE;
    }

    @Nullable
    public static Site searchByUrl(String url) {
        if (null == url || url.isEmpty()) {
            Timber.w("Invalid url");
            return null;
        }

        for (Site s : Site.values())
            if (s.code > 0 && HttpHelper.getDomainFromUri(url).equalsIgnoreCase(HttpHelper.getDomainFromUri(s.url)))
                return s;

        return Site.NONE;
    }

    public int getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public String getUrl() {
        return url;
    }

    public int getIco() {
        return ico;
    }

    public boolean useMobileAgent() {
        return useMobileAgent;
    }

    public boolean useHentoidAgent() {
        return useHentoidAgent;
    }

    public boolean hasImageProcessing() {
        return hasImageProcessing;
    }

    public boolean hasBackupURLs() {
        return hasBackupURLs;
    }

    public String getFolder() {
        if (this == FAKKU)
            return "Downloads";
        else
            return description;
    }

    public String getUserAgent() {
        if (useMobileAgent())
            return HttpHelper.getMobileUserAgent(useHentoidAgent());
        else
            return HttpHelper.getDesktopUserAgent(useHentoidAgent());
    }

    public static class SiteConverter implements PropertyConverter<Site, Long> {
        @Override
        public Site convertToEntityProperty(Long databaseValue) {
            if (databaseValue == null) {
                return Site.NONE;
            }
            for (Site site : Site.values()) {
                if (site.getCode() == databaseValue) {
                    return site;
                }
            }
            return Site.NONE;
        }

        @Override
        public Long convertToDatabaseValue(Site entityProperty) {
            return entityProperty == null ? null : (long) entityProperty.getCode();
        }
    }
}
