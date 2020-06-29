package me.devsaki.hentoid.database;

import android.content.Context;
import android.util.SparseIntArray;

import androidx.annotation.NonNull;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.annotation.Nullable;

import io.objectbox.Box;
import io.objectbox.BoxStore;
import io.objectbox.Property;
import io.objectbox.android.AndroidObjectBrowser;
import io.objectbox.query.LazyList;
import io.objectbox.query.Query;
import io.objectbox.query.QueryBuilder;
import me.devsaki.hentoid.BuildConfig;
import me.devsaki.hentoid.database.domains.Attribute;
import me.devsaki.hentoid.database.domains.AttributeLocation;
import me.devsaki.hentoid.database.domains.Attribute_;
import me.devsaki.hentoid.database.domains.Content;
import me.devsaki.hentoid.database.domains.Content_;
import me.devsaki.hentoid.database.domains.ErrorRecord;
import me.devsaki.hentoid.database.domains.ErrorRecord_;
import me.devsaki.hentoid.database.domains.ImageFile;
import me.devsaki.hentoid.database.domains.ImageFile_;
import me.devsaki.hentoid.database.domains.MyObjectBox;
import me.devsaki.hentoid.database.domains.QueueRecord;
import me.devsaki.hentoid.database.domains.QueueRecord_;
import me.devsaki.hentoid.database.domains.SiteHistory;
import me.devsaki.hentoid.database.domains.SiteHistory_;
import me.devsaki.hentoid.enums.AttributeType;
import me.devsaki.hentoid.enums.Site;
import me.devsaki.hentoid.enums.StatusContent;
import me.devsaki.hentoid.util.AttributeMap;
import me.devsaki.hentoid.util.Helper;
import me.devsaki.hentoid.util.Preferences;
import me.devsaki.hentoid.util.RandomSeedSingleton;
import timber.log.Timber;

import static com.annimon.stream.Collectors.toList;

public class ObjectBoxDB {

    // TODO - put indexes

    // Status displayed in the library view (all books of the library; both internal and external)
    private static final int[] libraryStatus = new int[]{StatusContent.DOWNLOADED.getCode(), StatusContent.MIGRATED.getCode(), StatusContent.EXTERNAL.getCode()};

    private static ObjectBoxDB instance;

    private final BoxStore store;


    private ObjectBoxDB(Context context) {
        final long maxSize = (long) 2 * 1024 * 1024; // 2Gb max size
        store = MyObjectBox.builder().androidContext(context.getApplicationContext()).maxSizeInKByte(maxSize).build();

        if (BuildConfig.DEBUG && BuildConfig.INCLUDE_OBJECTBOX_BROWSER) {
            boolean started = new AndroidObjectBrowser(store).start(context.getApplicationContext());
            Timber.i("ObjectBrowser started: %s", started);
        }
    }

    // For testing (store generated by the test framework)
    private ObjectBoxDB(BoxStore store) {
        this.store = store;
    }


    // Use this to get db instance
    public static synchronized ObjectBoxDB getInstance(Context context) {
        // Use application context only
        if (instance == null) {
            instance = new ObjectBoxDB(context);
        }

        return instance;
    }

    // Use this to get db instance for testing (store generated by the test framework)
    public static synchronized ObjectBoxDB getInstance(BoxStore store) {
        // Use application context only
        if (instance == null) {
            instance = new ObjectBoxDB(store);
        }

        return instance;
    }


    void closeThreadResources() {
        store.closeThreadResources();
    }


    long insertContent(Content content) {
        List<Attribute> attributes = content.getAttributes();
        Box<Attribute> attrBox = store.boxFor(Attribute.class);
        Query<Attribute> attrByUniqueKey = attrBox.query().equal(Attribute_.type, 0).equal(Attribute_.name, "").build();

        return store.callInTxNoException(() -> {
            // Master data management managed manually
            // Ensure all known attributes are replaced by their ID before being inserted
            // Watch https://github.com/objectbox/objectbox-java/issues/509 for a lighter solution based on @Unique annotation
            Attribute dbAttr;
            Attribute inputAttr;
            if (attributes != null)
                for (int i = 0; i < attributes.size(); i++) {
                    inputAttr = attributes.get(i);
                    dbAttr = (Attribute) attrByUniqueKey.setParameter(Attribute_.name, inputAttr.getName())
                            .setParameter(Attribute_.type, inputAttr.getType().getCode())
                            .findFirst();
                    if (dbAttr != null) {
                        attributes.set(i, dbAttr); // If existing -> set the existing attribute
                        dbAttr.addLocationsFrom(inputAttr);
                        attrBox.put(dbAttr);
                    } else {
                        inputAttr.setName(inputAttr.getName().toLowerCase().trim()); // If new -> normalize the attribute
                    }
                }

            return store.boxFor(Content.class).put(content);
        });
    }

    long countContentEntries() {
        return store.boxFor(Content.class).count();
    }

    public void updateContentStatus(@NonNull final StatusContent updateFrom, @NonNull final StatusContent updateTo) {
        List<Content> content = selectContentByStatus(updateFrom);
        for (int i = 0; i < content.size(); i++) content.get(i).setStatus(updateTo);

        store.boxFor(Content.class).put(content);
    }

    List<Content> selectContentByStatus(StatusContent status) {
        return selectContentByStatusCodes(new int[]{status.getCode()});
    }

    private List<Content> selectContentByStatusCodes(int[] statusCodes) {
        return store.boxFor(Content.class).query().in(Content_.status, statusCodes).build().find();
    }

    Query<Content> selectAllInternalBooksQ(boolean favsOnly) {
        // All statuses except SAVED, DOWNLOADING, PAUSED and ERROR that imply the book is in the download queue
        // and EXTERNAL because we only want to manage internal books here
        int[] storedContentStatus = new int[]{
                StatusContent.DOWNLOADED.getCode(),
                StatusContent.MIGRATED.getCode(),
                StatusContent.IGNORED.getCode(),
                StatusContent.UNHANDLED_ERROR.getCode(),
                StatusContent.CANCELED.getCode(),
                StatusContent.ONLINE.getCode()
        };
        QueryBuilder<Content> query = store.boxFor(Content.class).query().in(Content_.status, storedContentStatus);
        if (favsOnly) query.equal(Content_.favourite, true);
        return query.build();
    }

    Query<Content> selectAllExternalBooksQ() {
        return store.boxFor(Content.class).query().equal(Content_.status, StatusContent.EXTERNAL.getCode()).build();
    }

    Query<Content> selectAllErrorJsonBooksQ() {
        return store.boxFor(Content.class).query().equal(Content_.status, StatusContent.ERROR.getCode()).notNull(Content_.jsonUri).notEqual(Content_.jsonUri, "").build();
    }

    Query<Content> selectAllQueueBooksQ() {
        int[] storedContentStatus = new int[]{
                StatusContent.SAVED.getCode(),
                StatusContent.DOWNLOADING.getCode(),
                StatusContent.PAUSED.getCode(),
                StatusContent.ERROR.getCode()
        };
        return store.boxFor(Content.class).query().in(Content_.status, storedContentStatus).build();
    }

    void deleteContent(Content content) {
        deleteContentById(content.getId());
    }

    private void deleteContentById(long contentId) {
        deleteContentById(new long[]{contentId});
    }

    /**
     * Remove the given content and all related objects from the DB
     * NB : ObjectBox v2.3.1 does not support cascade delete, so everything has to be done manually
     *
     * @param contentId IDs of the contents to be removed from the DB
     */
    void deleteContentById(long[] contentId) {
        Box<ErrorRecord> errorBox = store.boxFor(ErrorRecord.class);
        Box<ImageFile> imageFileBox = store.boxFor(ImageFile.class);
        Box<Attribute> attributeBox = store.boxFor(Attribute.class);
        Box<AttributeLocation> locationBox = store.boxFor(AttributeLocation.class);
        Box<Content> contentBox = store.boxFor(Content.class);

        for (long id : contentId) {
            Content c = contentBox.get(id);
            if (c != null) {
                store.runInTx(() -> {
                    if (c.getImageFiles() != null) {
                        for (ImageFile i : c.getImageFiles())
                            imageFileBox.remove(i);   // Delete imageFiles
                        c.getImageFiles().clear();                                      // Clear links to all imageFiles
                    }

                    if (c.getErrorLog() != null) {
                        for (ErrorRecord e : c.getErrorLog())
                            errorBox.remove(e);   // Delete error records
                        c.getErrorLog().clear();                                    // Clear links to all errorRecords
                    }

                    // Delete attribute when current content is the only content left on the attribute
                    for (Attribute a : c.getAttributes())
                        if (1 == a.contents.size()) {
                            for (AttributeLocation l : a.getLocations())
                                locationBox.remove(l); // Delete all locations
                            a.getLocations().clear();                                           // Clear location links
                            attributeBox.remove(a);                                             // Delete the attribute itself
                        }
                    c.getAttributes().clear();                                      // Clear links to all attributes

                    contentBox.remove(c);                                           // Remove the content itself
                });
            }
        }
    }

    List<QueueRecord> selectQueue() {
        return store.boxFor(QueueRecord.class).query().order(QueueRecord_.rank).build().find();
    }

    List<Content> selectQueueContents() {
        List<Content> result = new ArrayList<>();
        List<QueueRecord> queueRecords = selectQueue();
        for (QueueRecord q : queueRecords) result.add(q.content.getTarget());
        return result;
    }

    Query<QueueRecord> selectQueueContentsQ() {
        return store.boxFor(QueueRecord.class).query().order(QueueRecord_.rank).build();
    }

    long selectMaxQueueOrder() {
        return store.boxFor(QueueRecord.class).query().build().property(QueueRecord_.rank).max();
    }

    void insertQueue(long id, int order) {
        store.boxFor(QueueRecord.class).put(new QueueRecord(id, order));
    }

    void updateQueue(@NonNull final List<QueueRecord> queue) {
        Box<QueueRecord> queueRecordBox = store.boxFor(QueueRecord.class);
        queueRecordBox.put(queue);
    }

    void deleteQueue(@NonNull Content content) {
        deleteQueue(content.getId());
    }

    void deleteQueue(int queueIndex) {
        store.boxFor(QueueRecord.class).remove(selectQueue().get(queueIndex).id);
    }

    void deleteQueue() {
        store.boxFor(QueueRecord.class).removeAll();
    }

    private void deleteQueue(long contentId) {
        Box<QueueRecord> queueRecordBox = store.boxFor(QueueRecord.class);
        QueueRecord record = queueRecordBox.query().equal(QueueRecord_.contentId, contentId).build().findFirst();

        if (record != null) queueRecordBox.remove(record);
    }

    Query<Content> selectVisibleContentQ() {
        return queryContentSearchContent("", Collections.emptyList(), false, Preferences.Constant.ORDER_FIELD_NONE, false);
    }

    @Nullable
    Content selectContentById(long id) {
        return store.boxFor(Content.class).get(id);
    }

    @Nullable
    Content selectContentBySourceAndUrl(@NonNull Site site, @NonNull String url) {
        return store.boxFor(Content.class).query().equal(Content_.url, url).equal(Content_.site, site.getCode()).build().findFirst();
    }

    private static long[] getIdsFromAttributes(@NonNull List<Attribute> attrs) {
        long[] result = new long[attrs.size()];
        if (!attrs.isEmpty()) {
            int index = 0;
            for (Attribute a : attrs) result[index++] = a.getId();
        }
        return result;
    }

    private void applySortOrder(QueryBuilder<Content> query, int orderField, boolean orderDesc) {
        // Random ordering is tricky (see https://github.com/objectbox/objectbox-java/issues/17)
        // => Implemented post-query build
        if (orderField == Preferences.Constant.ORDER_FIELD_RANDOM) return;

        Property<Content> field = getPropertyFromField(orderField);
        if (null == field) return;

        if (orderDesc) query.orderDesc(field);
        else query.order(field);

        // Specifics sub-sorting fields when ordering by reads
        if (orderField == Preferences.Constant.ORDER_FIELD_READS) {
            if (orderDesc) query.orderDesc(Content_.lastReadDate);
            else query.order(Content_.lastReadDate).orderDesc(Content_.downloadDate);
        }
    }

    @Nullable
    private Property<Content> getPropertyFromField(int prefsFieldCode) {
        switch (prefsFieldCode) {
            case Preferences.Constant.ORDER_FIELD_TITLE:
                return Content_.title;
            case Preferences.Constant.ORDER_FIELD_ARTIST:
                return Content_.author; // Might not be what users want when there are multiple authors
            case Preferences.Constant.ORDER_FIELD_NB_PAGES:
                return Content_.qtyPages;
            case Preferences.Constant.ORDER_FIELD_DOWNLOAD_DATE:
                return Content_.downloadDate;
            case Preferences.Constant.ORDER_FIELD_UPLOAD_DATE:
                return Content_.uploadDate;
            case Preferences.Constant.ORDER_FIELD_READ_DATE:
                return Content_.lastReadDate;
            case Preferences.Constant.ORDER_FIELD_READS:
                return Content_.reads;
            case Preferences.Constant.ORDER_FIELD_SIZE:
                return Content_.size;
            default:
                return null;
        }
    }

    Query<Content> queryContentSearchContent(
            String title,
            List<Attribute> metadata,
            boolean filterFavourites,
            int orderField,
            boolean orderDesc) {
        AttributeMap metadataMap = new AttributeMap();
        metadataMap.addAll(metadata);

        boolean hasTitleFilter = (title != null && title.length() > 0);
        boolean hasSiteFilter = metadataMap.containsKey(AttributeType.SOURCE)
                && (metadataMap.get(AttributeType.SOURCE) != null)
                && !(metadataMap.get(AttributeType.SOURCE).isEmpty());
        boolean hasTagFilter = metadataMap.keySet().size() > (hasSiteFilter ? 1 : 0);

        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, libraryStatus);

        if (hasSiteFilter)
            query.in(Content_.site, getIdsFromAttributes(metadataMap.get(AttributeType.SOURCE)));
        if (filterFavourites) query.equal(Content_.favourite, true);
        if (hasTitleFilter) query.contains(Content_.title, title);
        if (hasTagFilter) {
            for (Map.Entry<AttributeType, List<Attribute>> entry : metadataMap.entrySet()) {
                AttributeType attrType = entry.getKey();
                if (!attrType.equals(AttributeType.SOURCE)) { // Not a "real" attribute in database
                    List<Attribute> attrs = entry.getValue();
                    if (attrs != null && !attrs.isEmpty()) {
                        query.in(Content_.id, selectFilteredContent(attrs, false));
                    }
                }
            }
        }
        applySortOrder(query, orderField, orderDesc);

        return query.build();
    }

    private Query<Content> queryContentUniversalAttributes(String queryStr, boolean filterFavourites) {
        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, libraryStatus);

        if (filterFavourites) query.equal(Content_.favourite, true);
        query.link(Content_.attributes).contains(Attribute_.name, queryStr, QueryBuilder.StringOrder.CASE_INSENSITIVE);

        return query.build();
    }

    private Query<Content> queryContentUniversalContent(
            String queryStr,
            boolean filterFavourites,
            long[] additionalIds,
            int orderField,
            boolean orderDesc) {
        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, libraryStatus);

        if (filterFavourites) query.equal(Content_.favourite, true);
        query.contains(Content_.title, queryStr, QueryBuilder.StringOrder.CASE_INSENSITIVE);
        query.or().equal(Content_.uniqueSiteId, queryStr);
//        query.or().link(Content_.attributes).contains(Attribute_.name, queryStr, QueryBuilder.StringOrder.CASE_INSENSITIVE); // Use of or() here is not possible yet with ObjectBox v2.3.1
        query.or().in(Content_.id, additionalIds);
        applySortOrder(query, orderField, orderDesc);

        return query.build();
    }

    Query<Content> queryContentUniversal(
            String queryStr,
            boolean filterFavourites,
            int orderField,
            boolean orderDesc) {
        // Due to objectBox limitations (see https://github.com/objectbox/objectbox-java/issues/497 and https://github.com/objectbox/objectbox-java/issues/201)
        // querying Content and attributes have to be done separately
        Query<Content> contentAttrSubQuery = queryContentUniversalAttributes(queryStr, filterFavourites);
        return queryContentUniversalContent(queryStr, filterFavourites, contentAttrSubQuery.findIds(), orderField, orderDesc);
    }

    long countContentSearch(String title, List<Attribute> tags, boolean filterFavourites) {
        Query<Content> query = queryContentSearchContent(title, tags, filterFavourites, Preferences.Constant.ORDER_FIELD_NONE, false);
        return query.count();
    }

    private static long[] shuffleRandomSortId(Query<Content> query) {
        LazyList<Content> lazyList = query.findLazy();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < lazyList.size(); i++) order.add(i);
        Collections.shuffle(order, new Random(RandomSeedSingleton.getInstance().getSeed()));

        List<Long> result = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            result.add(lazyList.get(order.get(i)).getId());
        }
        return Helper.getPrimitiveLongArrayFromList(result);
    }

    long[] selectContentSearchId(String title, List<Attribute> tags, boolean filterFavourites, int orderField, boolean orderDesc) {
        long[] result;
        Query<Content> query = queryContentSearchContent(title, tags, filterFavourites, orderField, orderDesc);

        if (orderField != Preferences.Constant.ORDER_FIELD_RANDOM) {
            result = query.findIds();
        } else {
            result = shuffleRandomSortId(query);
        }
        return result;
    }

    long[] selectContentUniversalId(String queryStr, boolean filterFavourites, int orderField, boolean orderDesc) {
        long[] result;
        // Due to objectBox limitations (see https://github.com/objectbox/objectbox-java/issues/497 and https://github.com/objectbox/objectbox-java/issues/201)
        // querying Content and attributes have to be done separately
        Query<Content> contentAttrSubQuery = queryContentUniversalAttributes(queryStr, filterFavourites);
        Query<Content> query = queryContentUniversalContent(queryStr, filterFavourites, contentAttrSubQuery.findIds(), orderField, orderDesc);

        if (orderField != Preferences.Constant.ORDER_FIELD_RANDOM) {
            result = query.findIds();
        } else {
            result = shuffleRandomSortId(query);
        }
        return result;
    }

    long countContentUniversal(String queryStr, boolean filterFavourites) {
        // Due to objectBox limitations (see https://github.com/objectbox/objectbox-java/issues/497 and https://github.com/objectbox/objectbox-java/issues/201)
        // querying Content and attributes have to be done separately
        Query<Content> contentAttrSubQuery = queryContentUniversalAttributes(queryStr, filterFavourites);
        Query<Content> query = queryContentUniversalContent(queryStr, filterFavourites, contentAttrSubQuery.findIds(), Preferences.Constant.ORDER_FIELD_NONE, false);
        return query.count();
    }

    private long[] selectFilteredContent(List<Attribute> attrs, boolean filterFavourites) {
        if (null == attrs || attrs.isEmpty()) return new long[0];

        // Pre-build queries to reuse them efficiently within the loops
        QueryBuilder<Content> contentFromSourceQueryBuilder = store.boxFor(Content.class).query();
        contentFromSourceQueryBuilder.in(Content_.status, libraryStatus);
        contentFromSourceQueryBuilder.equal(Content_.site, 1);
        if (filterFavourites) contentFromSourceQueryBuilder.equal(Content_.favourite, true);
        Query<Content> contentFromSourceQuery = contentFromSourceQueryBuilder.build();

        QueryBuilder<Content> contentFromAttributesQueryBuilder = store.boxFor(Content.class).query();
        contentFromAttributesQueryBuilder.in(Content_.status, libraryStatus);
        if (filterFavourites) contentFromAttributesQueryBuilder.equal(Content_.favourite, true);
        contentFromAttributesQueryBuilder.link(Content_.attributes)
                .equal(Attribute_.type, 0)
                .equal(Attribute_.name, "");
        Query<Content> contentFromAttributesQuery = contentFromAttributesQueryBuilder.build();

        // Cumulative query loop
        // Each iteration restricts the results of the next because advanced search uses an AND logic
        List<Long> results = Collections.emptyList();
        long[] ids;

        for (Attribute attr : attrs) {
            if (attr.getType().equals(AttributeType.SOURCE)) {
                ids = contentFromSourceQuery.setParameter(Content_.site, attr.getId()).findIds();
            } else {
                ids = contentFromAttributesQuery.setParameter(Attribute_.type, attr.getType().getCode())
                        .setParameter(Attribute_.name, attr.getName()).findIds();
            }
            if (results.isEmpty()) results = Helper.getListFromPrimitiveArray(ids);
            else {
                // Filter results with newly found IDs (only common IDs should stay)
                List<Long> idsAsList = Helper.getListFromPrimitiveArray(ids);
                results.retainAll(idsAsList);
            }
        }

        return Helper.getPrimitiveLongArrayFromList(results);
    }

    List<Attribute> selectAvailableSources() {
        return selectAvailableSources(null);
    }

    List<Attribute> selectAvailableSources(List<Attribute> filter) {
        List<Attribute> result = new ArrayList<>();

        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, libraryStatus);

        if (filter != null && !filter.isEmpty()) {
            AttributeMap metadataMap = new AttributeMap();
            metadataMap.addAll(filter);

            List<Attribute> params = metadataMap.get(AttributeType.SOURCE);
            if (params != null && !params.isEmpty())
                query.in(Content_.site, getIdsFromAttributes(params));

            for (Map.Entry<AttributeType, List<Attribute>> entry : metadataMap.entrySet()) {
                AttributeType attrType = entry.getKey();
                if (!attrType.equals(AttributeType.SOURCE)) { // Not a "real" attribute in database
                    List<Attribute> attrs = entry.getValue();
                    if (attrs != null && !attrs.isEmpty())
                        query.in(Content_.id, selectFilteredContent(attrs, false));
                }
            }

        }

        List<Content> content = query.build().find();

        // SELECT field, COUNT(*) GROUP BY (field) is not implemented in ObjectBox v2.3.1
        // (see https://github.com/objectbox/objectbox-java/issues/422)
        // => Group by and count have to be done manually (thanks God Stream exists !)
        // Group and count by source
        Map<Site, List<Content>> map = Stream.of(content).collect(Collectors.groupingBy(Content::getSite));
        for (Map.Entry<Site, List<Content>> entry : map.entrySet()) {
            Site site = entry.getKey();
            int size = (null == entry.getValue()) ? 0 : entry.getValue().size();
            result.add(new Attribute(AttributeType.SOURCE, site.getDescription()).setExternalId(site.getCode()).setCount(size));
        }
        // Order by count desc
        result = Stream.of(result).sortBy(a -> -a.getCount()).collect(toList());

        return result;
    }

    Query<Content> selectErrorContentQ() {
        return store.boxFor(Content.class).query().equal(Content_.status, StatusContent.ERROR.getCode()).orderDesc(Content_.downloadDate).build();
    }

    private Query<Attribute> queryAvailableAttributes(
            @NonNull final AttributeType type,
            String filter,
            long[] filteredContent) {
        QueryBuilder<Attribute> query = store.boxFor(Attribute.class).query();
        query.equal(Attribute_.type, type.getCode());
        if (filter != null && !filter.trim().isEmpty())
            query.contains(Attribute_.name, filter.trim(), QueryBuilder.StringOrder.CASE_INSENSITIVE);
        if (filteredContent.length > 0)
            query.link(Attribute_.contents).in(Content_.id, filteredContent).in(Content_.status, libraryStatus);
        else
            query.link(Attribute_.contents).in(Content_.status, libraryStatus);

        return query.build();
    }

    long countAvailableAttributes(AttributeType
                                          type, List<Attribute> attributeFilter, String filter, boolean filterFavourites) {
        return queryAvailableAttributes(type, filter, selectFilteredContent(attributeFilter, filterFavourites)).count();
    }

    @SuppressWarnings("squid:S2184")
        // In our case, limit() argument has to be human-readable -> no issue concerning its type staying in the int range
    List<Attribute> selectAvailableAttributes(
            @NonNull AttributeType type,
            List<Attribute> attributeFilter,
            String filter,
            boolean filterFavourites,
            int sortOrder,
            int page,
            int itemsPerPage) {
        long[] filteredContent = selectFilteredContent(attributeFilter, filterFavourites);
        List<Long> filteredContentAsList = Helper.getListFromPrimitiveArray(filteredContent);
        List<Integer> libraryStatusAsList = Helper.getListFromPrimitiveArray(libraryStatus);
        List<Attribute> result = queryAvailableAttributes(type, filter, filteredContent).find();

        // Compute attribute count for sorting
        long count;
        for (Attribute a : result) {
            count = Stream.of(a.contents)
                    .filter(c -> libraryStatusAsList.contains(c.getStatus().getCode()))
                    .filter(c -> filteredContentAsList.isEmpty() || filteredContentAsList.contains(c.getId()))
                    .count();
            a.setCount((int) count);
        }

        // Apply sort order
        Stream<Attribute> s = Stream.of(result);
        if (Preferences.Constant.ORDER_ATTRIBUTES_ALPHABETIC == sortOrder) {
            s = s.sortBy(a -> -a.getCount()).sortBy(Attribute::getName);
        } else {
            s = s.sortBy(Attribute::getName).sortBy(a -> -a.getCount());
        }

        // Apply paging
        if (itemsPerPage > 0) {
            int start = (page - 1) * itemsPerPage;
            s = s.limit(page * itemsPerPage).skip(start); // squid:S2184 here because int * int -> int (not long)
        }
        return s.collect(toList());
    }

    SparseIntArray countAvailableAttributesPerType() {
        return countAvailableAttributesPerType(null);
    }

    SparseIntArray countAvailableAttributesPerType(List<Attribute> attributeFilter) {
        // Get Content filtered by current selection
        long[] filteredContent = selectFilteredContent(attributeFilter, false);
        // Get available attributes of the resulting content list
        QueryBuilder<Attribute> query = store.boxFor(Attribute.class).query();

        if (filteredContent.length > 0)
            query.link(Attribute_.contents).in(Content_.id, filteredContent).in(Content_.status, libraryStatus);
        else
            query.link(Attribute_.contents).in(Content_.status, libraryStatus);

        List<Attribute> attributes = query.build().find();

        SparseIntArray result = new SparseIntArray();
        // SELECT field, COUNT(*) GROUP BY (field) is not implemented in ObjectBox v2.3.1
        // (see https://github.com/objectbox/objectbox-java/issues/422)
        // => Group by and count have to be done manually (thanks God Stream exists !)
        // Group and count by type
        Map<AttributeType, List<Attribute>> map = Stream.of(attributes).collect(Collectors.groupingBy(Attribute::getType));

        for (Map.Entry<AttributeType, List<Attribute>> entry : map.entrySet()) {
            AttributeType t = entry.getKey();
            int size = (null == entry.getValue()) ? 0 : entry.getValue().size();
            result.append(t.getCode(), size);
        }

        return result;
    }

    void updateImageFileStatusParamsMimeTypeUriSize(@NonNull ImageFile image) {
        Box<ImageFile> imgBox = store.boxFor(ImageFile.class);
        ImageFile img = imgBox.get(image.getId());
        if (img != null) {
            img.setStatus(image.getStatus());
            img.setDownloadParams(image.getDownloadParams());
            img.setMimeType(image.getMimeType());
            img.setFileUri(image.getFileUri());
            img.setSize(image.getSize());
            imgBox.put(img);
        }
    }

    void updateImageContentStatus(
            long contentId,
            @Nullable StatusContent updateFrom,
            @NonNull StatusContent updateTo) {
        QueryBuilder<ImageFile> query = store.boxFor(ImageFile.class).query();
        if (updateFrom != null) query.equal(ImageFile_.status, updateFrom.getCode());
        List<ImageFile> imgs = query.equal(ImageFile_.contentId, contentId).build().find();

        if (imgs.isEmpty()) return;

        for (int i = 0; i < imgs.size(); i++) imgs.get(i).setStatus(updateTo);
        store.boxFor(ImageFile.class).put(imgs);
    }

    void updateImageFileUrl(@NonNull final ImageFile image) {
        Box<ImageFile> imgBox = store.boxFor(ImageFile.class);
        ImageFile img = imgBox.get(image.getId());
        if (img != null) {
            img.setUrl(image.getUrl());
            imgBox.put(img);
        }
    }

    // Returns a list of processed images grouped by status, with count and filesize
    Map<StatusContent, ImmutablePair<Integer, Long>> countProcessedImagesById(long contentId) {
        QueryBuilder<ImageFile> imgQuery = store.boxFor(ImageFile.class).query();
        imgQuery.equal(ImageFile_.contentId, contentId);
        List<ImageFile> images = imgQuery.build().find();

        Map<StatusContent, ImmutablePair<Integer, Long>> result = new EnumMap<>(StatusContent.class);
        // SELECT field, COUNT(*) GROUP BY (field) is not implemented in ObjectBox v2.3.1
        // (see https://github.com/objectbox/objectbox-java/issues/422)
        // => Group by and count have to be done manually (thanks God Stream exists !)
        // Group and count by type
        Map<StatusContent, List<ImageFile>> map = Stream.of(images).collect(Collectors.groupingBy(ImageFile::getStatus));
        for (Map.Entry<StatusContent, List<ImageFile>> entry : map.entrySet()) {
            StatusContent t = entry.getKey();
            int count = 0;
            long size = 0;
            if (entry.getValue() != null) {
                count = entry.getValue().size();
                for (ImageFile img : entry.getValue()) size += img.getSize();
            }
            result.put(t, new ImmutablePair<>(count, size));
        }

        return result;
    }

    Map<Site, ImmutablePair<Integer, Long>> selectMemoryUsagePerSource() {
        // Get all downloaded images regardless of the book's status
        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, new int[]{StatusContent.DOWNLOADED.getCode(), StatusContent.MIGRATED.getCode()});
        List<Content> books = query.build().find();

        Map<Site, ImmutablePair<Integer, Long>> result = new EnumMap<>(Site.class);
        // SELECT field, COUNT(*) GROUP BY (field) is not implemented in ObjectBox v2.3.1
        // (see https://github.com/objectbox/objectbox-java/issues/422)
        // => Group by and count have to be done manually (thanks God Stream exists !)
        // Group and count by type
        Map<Site, List<Content>> map = Stream.of(books).collect(Collectors.groupingBy(Content::getSite));
        for (Map.Entry<Site, List<Content>> entry : map.entrySet()) {
            Site s = entry.getKey();
            int count = 0;
            long size = 0;
            if (entry.getValue() != null) {
                count = entry.getValue().size();
                for (Content c : entry.getValue()) size += c.getSize();
            }
            result.put(s, new ImmutablePair<>(count, size));
        }

        return result;
    }

    void insertErrorRecord(@NonNull final ErrorRecord record) {
        store.boxFor(ErrorRecord.class).put(record);
    }

    List<ErrorRecord> selectErrorRecordByContentId(long contentId) {
        return store.boxFor(ErrorRecord.class).query().equal(ErrorRecord_.contentId, contentId).build().find();
    }

    void deleteErrorRecords(long contentId) {
        List<ErrorRecord> records = selectErrorRecordByContentId(contentId);
        store.boxFor(ErrorRecord.class).remove(records);
    }

    void insertImageFile(@NonNull ImageFile img) {
        if (img.getId() > 0) store.boxFor(ImageFile.class).put(img);
    }

    void deleteImageFiles(long contentId) {
        store.boxFor(ImageFile.class).query().equal(ImageFile_.contentId, contentId).build().remove();
    }

    void deleteImageFile(long imageId) {
        store.boxFor(ImageFile.class).remove(imageId);
    }

    void insertImageFiles(@NonNull List<ImageFile> imgs) {
        store.boxFor(ImageFile.class).put(imgs);
    }

    @Nullable
    ImageFile selectImageFile(long id) {
        if (id > 0) return store.boxFor(ImageFile.class).get(id);
        else return null;
    }

    Query<ImageFile> selectDownloadedImagesFromContent(long id) {
        QueryBuilder<ImageFile> builder = store.boxFor(ImageFile.class).query();
        builder.equal(ImageFile_.contentId, id);
        builder.in(ImageFile_.status, new int[]{StatusContent.DOWNLOADED.getCode(), StatusContent.EXTERNAL.getCode()});
        builder.order(ImageFile_.order);
        return builder.build();
    }

    void insertSiteHistory(@NonNull Site site, @NonNull String url) {
        SiteHistory siteHistory = selectHistory(site);
        if (siteHistory != null) {
            siteHistory.setUrl(url);
            store.boxFor(SiteHistory.class).put(siteHistory);
        } else {
            store.boxFor(SiteHistory.class).put(new SiteHistory(site, url));
        }
    }

    @Nullable
    SiteHistory selectHistory(@NonNull Site s) {
        return store.boxFor(SiteHistory.class).query().equal(SiteHistory_.site, s.getCode()).build().findFirst();
    }


    /**
     * ONE-SHOT USE QUERIES (MIGRATION & MAINTENANCE)
     */

    List<Content> selectContentWithOldPururinHost() {
        return store.boxFor(Content.class).query().contains(Content_.coverImageUrl, "://api.pururin.io/images/").build().find();
    }

    List<Content> selectContentWithOldTsuminoCovers() {
        return store.boxFor(Content.class).query().contains(Content_.coverImageUrl, "://www.tsumino.com/Image/Thumb/").build().find();
    }

    List<Content> selectDownloadedContentWithNoSize() {
        return store.boxFor(Content.class).query().in(Content_.status, libraryStatus).isNull(Content_.size).build().find();
    }

    public Query<Content> selectOldStoredContentQ() {
        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        query.in(Content_.status, new int[]{
                StatusContent.DOWNLOADING.getCode(),
                StatusContent.PAUSED.getCode(),
                StatusContent.DOWNLOADED.getCode(),
                StatusContent.ERROR.getCode(),
                StatusContent.MIGRATED.getCode()});
        query.notNull(Content_.storageFolder);
        query.notEqual(Content_.storageFolder, "");
        return query.build();
    }

    long[] selectStoredContentIds(boolean nonFavouritesOnly, boolean includeQueued) {
        QueryBuilder<Content> query = store.boxFor(Content.class).query();
        if (includeQueued)
            query.in(Content_.status, new int[]{
                    StatusContent.DOWNLOADING.getCode(),
                    StatusContent.PAUSED.getCode(),
                    StatusContent.DOWNLOADED.getCode(),
                    StatusContent.ERROR.getCode(),
                    StatusContent.MIGRATED.getCode()});
        else
            query.in(Content_.status, new int[]{
                    StatusContent.DOWNLOADED.getCode(),
                    StatusContent.MIGRATED.getCode()});
        query.notNull(Content_.storageUri);
        query.notEqual(Content_.storageUri, "");
        if (nonFavouritesOnly) query.equal(Content_.favourite, false);
        return query.build().findIds();
    }

}
