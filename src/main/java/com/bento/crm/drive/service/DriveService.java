package com.bento.crm.drive.service;

import com.bento.crm.common.context.TenantContext;
import com.bento.crm.common.exception.ResourceNotFoundException;
import com.bento.crm.common.security.CurrentUser;
import com.bento.crm.drive.dto.DriveDtos.FileResponse;
import com.bento.crm.drive.dto.DriveDtos.FileUpdateRequest;
import com.bento.crm.drive.dto.DriveDtos.FolderRequest;
import com.bento.crm.drive.dto.DriveDtos.FolderResponse;
import com.bento.crm.drive.dto.DriveDtos.Listing;
import com.bento.crm.drive.dto.DriveDtos.PublicFile;
import com.bento.crm.drive.dto.DriveDtos.ShareResponse;
import com.bento.crm.drive.model.DriveFolder;
import com.bento.crm.drive.model.FileShareLink;
import com.bento.crm.drive.repository.DriveFolderRepository;
import com.bento.crm.drive.repository.FileShareLinkRepository;
import com.bento.crm.file.model.StoredFile;
import com.bento.crm.file.repository.StoredFileRepository;
import com.bento.crm.file.service.FileStorageService;
import com.bento.crm.partner.model.Partner;
import com.bento.crm.partner.repository.PartnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The document drive: folders, tags, search and expiring share links over {@code stored_file}.
 *
 * <p>Files attached to records (an invoice's PDF, a deal's proposal) stay out of the folder tree
 * but show up in search and in a partner's view, for users who may read that kind of record.
 * Folders the system files financial documents into require an extra authority.
 */
@Service
@RequiredArgsConstructor
public class DriveService {

    public static final String VENDOR_INVOICES = "VENDOR_INVOICES";
    public static final String BANK_STATEMENTS = "BANK_STATEMENTS";
    public static final String CLIENTS = "CLIENTS";
    public static final String RECEIVED = "RECEIVED";

    /** Which authority lets a user see files attached to each kind of record. */
    private static final Map<String, String> OWNER_AUTHORITY = Map.of(
            "INVOICE", "INVOICES_READ",
            "EXPENSE", "PAYMENTS_READ",
            "DEAL", "DEALS_READ",
            "PROPOSAL", "PROPOSALS_READ",
            "PURCHASE_ORDER", "PURCHASE_ORDERS_READ",
            "TICKET", "TICKETS_READ",
            "PARTNER", "PARTNERS_READ",
            "INBOUND_DOCUMENT", "INVOICES_READ");

    private static final int MAX_TAGS = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DriveFolderRepository folderRepository;
    private final FileShareLinkRepository shareRepository;
    private final StoredFileRepository fileRepository;
    private final FileStorageService fileStorageService;
    private final PartnerRepository partnerRepository;
    private final JdbcTemplate jdbc;

    @Value("${app.frontend-url:http://localhost:3000}")
    private String frontendUrl;

    // Folders

    public Listing list(UUID folderId, String q, String tag, UUID partnerId, int page, int size) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        DriveFolder folder = folderId == null ? null : visibleFolder(orgId, folderId);
        boolean searching = notBlank(q) || notBlank(tag) || partnerId != null;

        List<FolderResponse> folders = searching ? List.of()
                : (folder == null ? folderRepository.roots(orgId) : folderRepository.children(orgId, folder.getId()))
                    .stream().filter(this::canSee).map(FolderResponse::of).toList();

        StringBuilder where = new StringBuilder(" FROM stored_file f LEFT JOIN drive_folder d ON d.id = f.folder_id"
                + " WHERE f.organization_id = ? AND (f.owner_entity_type IS NULL OR f.owner_entity_type <> 'ORGANIZATION')");
        List<Object> args = new ArrayList<>();
        args.add(orgId);
        Set<String> auths = CurrentUser.authorities();
        where.append(" AND (d.required_authority IS NULL").append(inList("d.required_authority", auths, args)).append(')');
        List<String> ownerTypes = OWNER_AUTHORITY.entrySet().stream()
                .filter(e -> auths.contains(e.getValue())).map(Map.Entry::getKey).toList();
        where.append(" AND (f.owner_entity_type IS NULL").append(inList("f.owner_entity_type", ownerTypes, args)).append(')');

        if (!searching) {
            if (folder == null) {
                where.append(" AND f.folder_id IS NULL AND f.owner_entity_type IS NULL");
            } else {
                where.append(" AND f.folder_id = ?");
                args.add(folder.getId());
            }
        }
        if (notBlank(q)) {
            String like = "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" AND (lower(f.file_name) LIKE ? OR lower(coalesce(f.description, '')) LIKE ?"
                    + " OR EXISTS (SELECT 1 FROM unnest(f.tags) t WHERE lower(t) LIKE ?))");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (notBlank(tag)) {
            where.append(" AND ? = ANY(f.tags)");
            args.add(normalizeTag(tag));
        }
        if (partnerId != null) {
            where.append(" AND (f.partner_id = ? OR (f.owner_entity_type = 'PARTNER' AND f.owner_entity_id = ?)"
                    + " OR d.partner_id = ?)");
            args.add(partnerId);
            args.add(partnerId);
            args.add(partnerId);
        }

        Long total = jdbc.queryForObject("SELECT count(*)" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(Math.max(1, Math.min(size, 200)));
        pageArgs.add(Math.max(0, page) * (long) Math.max(1, Math.min(size, 200)));
        List<UUID> ids = jdbc.queryForList("SELECT f.id" + where
                + " ORDER BY f.uploaded_at DESC NULLS LAST, f.created_at DESC LIMIT ? OFFSET ?", UUID.class, pageArgs.toArray());

        return new Listing(folder == null ? null : FolderResponse.of(folder), path(orgId, folder), folders,
                toResponses(load(ids)), total == null ? 0 : total);
    }

    @Transactional
    public FolderResponse createFolder(FolderRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        DriveFolder parent = r.parentId() == null ? null : visibleFolder(orgId, r.parentId());
        if (parent != null && parent.getSystemKey() != null && !CLIENTS.equals(parent.getSystemKey())
                && parent.getPartnerId() == null) {
            // System folders (supplier invoices, statements, received) are filled by the app, month by month.
            throw new IllegalStateException("Folders cannot be added inside this system folder");
        }
        DriveFolder f = new DriveFolder();
        f.setOrganizationId(orgId);
        f.setParentId(parent == null ? null : parent.getId());
        f.setName(cleanName(r.name()));
        f.setPartnerId(checkedPartner(orgId, r.partnerId()));
        // A sub-folder of a restricted folder is just as sensitive.
        f.setRequiredAuthority(parent == null ? null : parent.getRequiredAuthority());
        ensureUniqueName(orgId, f.getParentId(), f.getName(), null);
        return FolderResponse.of(folderRepository.save(f));
    }

    @Transactional
    public FolderResponse updateFolder(UUID id, FolderRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        DriveFolder f = visibleFolder(orgId, id);
        if (f.getSystemKey() != null) {
            throw new IllegalStateException("This folder is managed by Bento and cannot be changed");
        }
        UUID parentId = r.parentId();
        if (parentId != null) {
            DriveFolder parent = visibleFolder(orgId, parentId);
            for (DriveFolder p = parent; p != null; p = p.getParentId() == null ? null : folderRepository.find(orgId, p.getParentId()).orElse(null)) {
                if (p.getId().equals(f.getId())) {
                    throw new IllegalArgumentException("A folder cannot be moved inside itself");
                }
            }
        }
        f.setName(cleanName(r.name()));
        f.setParentId(parentId);
        f.setPartnerId(checkedPartner(orgId, r.partnerId()));
        ensureUniqueName(orgId, parentId, f.getName(), f.getId());
        return FolderResponse.of(folderRepository.save(f));
    }

    @Transactional
    public void deleteFolder(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        DriveFolder f = visibleFolder(orgId, id);
        if (f.getSystemKey() != null) {
            throw new IllegalStateException("This folder is managed by Bento and cannot be deleted");
        }
        Integer files = jdbc.queryForObject("SELECT count(*) FROM stored_file WHERE organization_id = ? AND folder_id = ?",
                Integer.class, orgId, id);
        if ((files != null && files > 0) || !folderRepository.children(orgId, id).isEmpty()) {
            throw new IllegalStateException("Empty the folder before deleting it");
        }
        f.setDeletedAt(Instant.now());
        folderRepository.save(f);
    }

    /** Every folder the user can see, for move pickers. */
    public List<FolderResponse> allFolders() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return folderRepository.all(orgId).stream().filter(this::canSee)
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName())).map(FolderResponse::of).toList();
    }

    public List<String> tags() {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        return jdbc.queryForList("SELECT DISTINCT unnest(tags) AS t FROM stored_file WHERE organization_id = ? ORDER BY t",
                String.class, orgId);
    }

    // Files

    @Transactional
    public FileResponse upload(MultipartFile file, UUID folderId, List<String> tags, UUID partnerId, String description) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        DriveFolder folder = folderId == null ? null : visibleFolder(orgId, folderId);
        UUID partner = checkedPartner(orgId, partnerId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }
        StoredFile f = fileStorageService.storeBytes(orgId, bytes, file.getOriginalFilename(), file.getContentType(),
                null, null, StoredFile.Source.UPLOAD, folder == null ? null : folder.getId());
        f.setUploadedByUserId(CurrentUser.idOrNull());
        f.setTags(normalizeTags(tags));
        f.setPartnerId(partner != null ? partner : folder == null ? null : folder.getPartnerId());
        f.setDescription(trimToNull(description, 500));
        return toResponses(List.of(fileRepository.save(f))).get(0);
    }

    @Transactional
    public FileResponse updateFile(UUID id, FileUpdateRequest r) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StoredFile f = visibleFile(orgId, id);
        if (notBlank(r.fileName())) {
            f.setFileName(r.fileName().trim().replaceAll("[\\p{Cntrl}\"\\\\/]", ""));
        }
        if (Boolean.TRUE.equals(r.moveToRoot())) {
            f.setFolderId(null);
        } else if (r.folderId() != null) {
            DriveFolder target = visibleFolder(orgId, r.folderId());
            f.setFolderId(target.getId());
            if (f.getPartnerId() == null) {
                f.setPartnerId(target.getPartnerId());
            }
        }
        if (r.tags() != null) {
            f.setTags(normalizeTags(r.tags()));
        }
        if (r.description() != null) {
            f.setDescription(trimToNull(r.description(), 500));
        }
        if (Boolean.TRUE.equals(r.unlinkPartner())) {
            f.setPartnerId(null);
        } else if (r.partnerId() != null) {
            f.setPartnerId(checkedPartner(orgId, r.partnerId()));
        }
        return toResponses(List.of(fileRepository.save(f))).get(0);
    }

    @Transactional
    public void deleteFile(UUID id) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StoredFile f = visibleFile(orgId, id);
        if (f.getOwnerEntityType() != null && !"DRIVE".equals(f.getOwnerEntityType())) {
            throw new IllegalStateException("This file is attached to a record. Remove it from there.");
        }
        fileStorageService.delete(f.getId());
    }

    public StoredFile fileForDownload(UUID id) {
        return visibleFile(TenantContext.getCurrentOrganizationId(), id);
    }

    public Resource open(StoredFile f) {
        return fileStorageService.open(f);
    }

    // Share links

    @Transactional
    public ShareResponse share(UUID fileId, Integer expiresInDays) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        StoredFile f = visibleFile(orgId, fileId);
        int days = expiresInDays == null ? 7 : Math.max(1, Math.min(90, expiresInDays));
        FileShareLink link = new FileShareLink();
        link.setOrganizationId(orgId);
        link.setFileId(f.getId());
        link.setToken(newToken());
        link.setExpiresAt(Instant.now().plus(days, ChronoUnit.DAYS));
        return toShare(shareRepository.save(link));
    }

    public List<ShareResponse> shares(UUID fileId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        visibleFile(orgId, fileId);
        return shareRepository.forFile(orgId, fileId).stream().map(this::toShare).toList();
    }

    @Transactional
    public void revokeShare(UUID linkId) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        FileShareLink link = shareRepository.find(orgId, linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Share link not found"));
        visibleFile(orgId, link.getFileId());
        link.setRevokedAt(Instant.now());
        shareRepository.save(link);
    }

    /** What a share link points to, for the public page. Expired and revoked links are not found. */
    public PublicFile publicFile(String token) {
        FileShareLink link = activeLink(token);
        StoredFile f = fileRepository.findById(link.getFileId())
                .orElseThrow(() -> new ResourceNotFoundException("This link is no longer valid"));
        return new PublicFile(f.getFileName(), f.getContentType(), f.getSizeBytes(), link.getExpiresAt());
    }

    @Transactional
    public Map.Entry<StoredFile, Resource> publicDownload(String token) {
        FileShareLink link = activeLink(token);
        StoredFile f = fileRepository.findById(link.getFileId())
                .orElseThrow(() -> new ResourceNotFoundException("This link is no longer valid"));
        jdbc.update("UPDATE file_share_link SET download_count = download_count + 1 WHERE id = ?", link.getId());
        return Map.entry(f, fileStorageService.open(f));
    }

    // Folders the system files documents into

    /**
     * The folder an incoming document goes to, created on first use: vendor invoices and bank
     * statements by month, a partner's documents in their own folder under Clients.
     */
    @Transactional
    public DriveFolder systemFolder(UUID orgId, String kind, YearMonth month) {
        return switch (kind) {
            case VENDOR_INVOICES -> monthFolder(orgId, ensure(orgId, VENDOR_INVOICES, "Factures fournisseurs", null, "INVOICES_READ", null), month);
            case BANK_STATEMENTS -> monthFolder(orgId, ensure(orgId, BANK_STATEMENTS, "Relevés bancaires", null, "PAYMENTS_READ", null), month);
            case CLIENTS -> ensure(orgId, CLIENTS, "Clients", null, null, null);
            default -> ensure(orgId, RECEIVED, "Documents reçus", null, null, null);
        };
    }

    @Transactional
    public DriveFolder partnerFolder(UUID orgId, UUID partnerId) {
        Partner partner = partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new ResourceNotFoundException("Partner not found"));
        DriveFolder clients = systemFolder(orgId, CLIENTS, null);
        return ensure(orgId, "PARTNER:" + partnerId, displayName(partner), clients.getId(), null, partnerId);
    }

    private DriveFolder monthFolder(UUID orgId, DriveFolder parent, YearMonth month) {
        YearMonth m = month == null ? YearMonth.now() : month;
        return ensure(orgId, parent.getSystemKey() + ":" + m, m.toString(), parent.getId(), parent.getRequiredAuthority(), null);
    }

    private DriveFolder ensure(UUID orgId, String key, String name, UUID parentId, String authority, UUID partnerId) {
        var existing = folderRepository.findBySystemKey(orgId, key);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Insert-if-absent so two documents arriving together cannot fail on the unique indexes.
        // A user folder may already hold the name: fall back to a suffixed one.
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = attempt == 0 ? name : name + " (" + (attempt + 1) + ")";
            jdbc.update("INSERT INTO drive_folder (organization_id, parent_id, name, partner_id, system_key, required_authority)"
                    + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING", orgId, parentId, candidate, partnerId, key, authority);
            existing = folderRepository.findBySystemKey(orgId, key);
            if (existing.isPresent()) {
                return existing.get();
            }
        }
        throw new IllegalStateException("Could not create folder " + name);
    }

    // Helpers

    private boolean canSee(DriveFolder f) {
        return f.getRequiredAuthority() == null || CurrentUser.hasAuthority(f.getRequiredAuthority());
    }

    private DriveFolder visibleFolder(UUID orgId, UUID id) {
        DriveFolder f = folderRepository.find(orgId, id).orElseThrow(() -> new ResourceNotFoundException("Folder not found"));
        if (!canSee(f)) {
            throw new ResourceNotFoundException("Folder not found");
        }
        return f;
    }

    /**
     * Whether the current user may open this file through the older /files endpoints: the same
     * owner and folder checks as the drive (supplier bills and bank statements need finance
     * rights). Organization files (logos) stay readable, they are public already.
     */
    public boolean readable(StoredFile f) {
        if ("ORGANIZATION".equals(f.getOwnerEntityType())) {
            return true;
        }
        String ownerAuthority = f.getOwnerEntityType() == null ? null : OWNER_AUTHORITY.get(f.getOwnerEntityType());
        if (ownerAuthority != null && !CurrentUser.hasAuthority(ownerAuthority)) {
            return false;
        }
        return f.getFolderId() == null || folderRepository.find(f.getOrganizationId(), f.getFolderId()).map(this::canSee).orElse(true);
    }

    private StoredFile visibleFile(UUID orgId, UUID id) {
        StoredFile f = fileRepository.findByOrganizationIdAndId(orgId, id)
                .orElseThrow(() -> new ResourceNotFoundException("File not found"));
        if ("ORGANIZATION".equals(f.getOwnerEntityType())) {
            throw new ResourceNotFoundException("File not found");
        }
        String ownerAuthority = f.getOwnerEntityType() == null ? null : OWNER_AUTHORITY.get(f.getOwnerEntityType());
        if (ownerAuthority != null && !CurrentUser.hasAuthority(ownerAuthority)) {
            throw new ResourceNotFoundException("File not found");
        }
        if (f.getFolderId() != null) {
            folderRepository.find(orgId, f.getFolderId()).filter(folder -> !canSee(folder))
                    .ifPresent(folder -> { throw new ResourceNotFoundException("File not found"); });
        }
        return f;
    }

    private List<FolderResponse> path(UUID orgId, DriveFolder folder) {
        List<FolderResponse> path = new ArrayList<>();
        for (DriveFolder f = folder; f != null && path.size() < 20;
             f = f.getParentId() == null ? null : folderRepository.find(orgId, f.getParentId()).orElse(null)) {
            path.add(0, FolderResponse.of(f));
        }
        return path;
    }

    private List<StoredFile> load(List<UUID> ids) {
        Map<UUID, StoredFile> byId = fileRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(StoredFile::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }

    public List<FileResponse> toResponses(List<StoredFile> files) {
        UUID orgId = TenantContext.getCurrentOrganizationId();
        Map<UUID, String> names = new HashMap<>();
        for (StoredFile f : files) {
            if (f.getPartnerId() != null && !names.containsKey(f.getPartnerId())) {
                names.put(f.getPartnerId(), partnerRepository.findByOrganizationIdAndId(orgId, f.getPartnerId())
                        .map(DriveService::displayName).orElse(null));
            }
        }
        return files.stream().map(f -> FileResponse.of(f, f.getPartnerId() == null ? null : names.get(f.getPartnerId()))).toList();
    }

    private ShareResponse toShare(FileShareLink l) {
        return new ShareResponse(l.getId(), frontendUrl.replaceAll("/+$", "") + "/f/" + l.getToken(), l.getExpiresAt(),
                l.isActive(Instant.now()), l.getDownloadCount(), l.getCreatedAt());
    }

    private FileShareLink activeLink(String token) {
        if (token == null || token.length() < 20 || token.length() > 64) {
            throw new ResourceNotFoundException("This link is no longer valid");
        }
        FileShareLink link = shareRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("This link is no longer valid"));
        if (!link.isActive(Instant.now())) {
            throw new ResourceNotFoundException("This link is no longer valid");
        }
        return link;
    }

    private UUID checkedPartner(UUID orgId, UUID partnerId) {
        if (partnerId == null) {
            return null;
        }
        partnerRepository.findByOrganizationIdAndId(orgId, partnerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown partner"));
        return partnerId;
    }

    private void ensureUniqueName(UUID orgId, UUID parentId, String name, UUID self) {
        List<DriveFolder> siblings = parentId == null ? folderRepository.roots(orgId) : folderRepository.children(orgId, parentId);
        if (siblings.stream().anyMatch(s -> !s.getId().equals(self) && s.getName().equalsIgnoreCase(name))) {
            throw new IllegalArgumentException("A folder with this name already exists here");
        }
    }

    static String[] normalizeTags(Collection<String> tags) {
        if (tags == null) {
            return new String[0];
        }
        Set<String> out = new LinkedHashSet<>();
        for (String t : tags) {
            String n = normalizeTag(t);
            if (n != null) {
                out.add(n);
            }
        }
        if (out.size() > MAX_TAGS) {
            throw new IllegalArgumentException("At most " + MAX_TAGS + " tags per file");
        }
        return out.toArray(String[]::new);
    }

    static String normalizeTag(String t) {
        if (t == null) {
            return null;
        }
        String n = t.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-").replaceAll("[\\p{Cntrl},\"]", "");
        if (n.isEmpty()) {
            return null;
        }
        return n.length() > 40 ? n.substring(0, 40) : n;
    }

    public static String displayName(Partner p) {
        return notBlank(p.getCompanyName()) ? p.getCompanyName() : p.getName();
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.trim().replaceAll("[\\p{Cntrl}/\\\\]", "");
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Folder name is required");
        }
        return n.length() > 120 ? n.substring(0, 120) : n;
    }

    private static String inList(String column, Collection<String> values, List<Object> args) {
        if (values.isEmpty()) {
            return "";
        }
        args.addAll(values);
        return " OR " + column + " IN (" + String.join(", ", java.util.Collections.nCopies(values.size(), "?")) + ")";
    }

    private static String newToken() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String trimToNull(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
