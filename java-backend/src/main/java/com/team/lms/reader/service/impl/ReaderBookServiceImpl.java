package com.team.lms.reader.service.impl;

import com.team.lms.common.enums.BorrowRequestStatus;
import com.team.lms.common.enums.BorrowRecordStatus;
import com.team.lms.common.enums.ReservationStatus;
import com.team.lms.common.enums.RoleType;
import com.team.lms.common.support.CurrentUserSupport;
import com.team.lms.common.support.PermissionScopeSupport;
import com.team.lms.common.support.SystemConfigSupport;
import com.team.lms.entity.Book;
import com.team.lms.entity.BookFavorite;
import com.team.lms.entity.BookReview;
import com.team.lms.entity.BorrowRecord;
import com.team.lms.entity.BorrowRequest;
import com.team.lms.entity.Fine;
import com.team.lms.entity.Inventory;
import com.team.lms.entity.Reservation;
import com.team.lms.entity.User;
import com.team.lms.exception.BusinessException;
import com.team.lms.mapper.BookFavoriteMapper;
import com.team.lms.mapper.BookMapper;
import com.team.lms.mapper.BookReviewMapper;
import com.team.lms.mapper.BorrowRecordMapper;
import com.team.lms.mapper.BorrowRequestMapper;
import com.team.lms.mapper.FineMapper;
import com.team.lms.mapper.InventoryMapper;
import com.team.lms.mapper.ReservationMapper;
import com.team.lms.reader.dto.ReaderBorrowRequestCreateRequest;
import com.team.lms.reader.dto.ReaderBookReviewCreateRequest;
import com.team.lms.reader.service.ReaderBookService;
import com.team.lms.reader.vo.ReaderBookDetailVo;
import com.team.lms.reader.vo.ReaderBookVo;
import com.team.lms.reader.vo.ReaderBorrowRecordVo;
import com.team.lms.reader.vo.ReaderBookReviewVo;
import com.team.lms.reader.vo.ReaderBorrowRequestVo;
import com.team.lms.reader.vo.ReaderFavoriteToggleVo;
import com.team.lms.reader.vo.ReaderReservationVo;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReaderBookServiceImpl implements ReaderBookService {

    private final BookMapper bookMapper;
    private final BookFavoriteMapper bookFavoriteMapper;
    private final BookReviewMapper bookReviewMapper;
    private final InventoryMapper inventoryMapper;
    private final BorrowRequestMapper borrowRequestMapper;
    private final BorrowRecordMapper borrowRecordMapper;
    private final ReservationMapper reservationMapper;
    private final FineMapper fineMapper;
    private final CurrentUserSupport currentUserSupport;
    private final PermissionScopeSupport permissionScopeSupport;
    private final SystemConfigSupport systemConfigSupport;

    @Override
    public List<ReaderBookVo> listVisibleBooks(String authorizationHeader, String keyword) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("BOOK_SEARCH", "BOOK_VIEW")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);

        List<Book> books = (keyword == null || keyword.isBlank())
                ? bookMapper.selectAllVisible()
                : bookMapper.selectVisibleByKeyword(keyword.trim());

        return toBookVoList(reader, books);
    }

    @Override
    public List<ReaderBookVo> listFavoriteBooks(String authorizationHeader) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("BOOK_SEARCH", "BOOK_VIEW")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);

        List<Book> books = bookFavoriteMapper.selectByReaderId(reader.getId()).stream()
                .map(BookFavorite::getBook)
                .map(bookRef -> bookRef == null ? null : bookMapper.selectById(bookRef.getId()))
                .filter(book -> book != null && !Boolean.TRUE.equals(book.getDeleted()))
                .toList();
        return toBookVoList(reader, books);
    }

    @Override
    public ReaderBookDetailVo getBookDetail(String authorizationHeader, Long bookId) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("BOOK_SEARCH", "BOOK_VIEW", "BORROW_REQUEST", "RESERVATION")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);

        Book book = requireBook(bookId);
        Inventory inventory = inventoryMapper.selectByBookId(bookId);
        List<BookReview> reviews = bookReviewMapper.selectByBookId(bookId);
        boolean favorite = bookFavoriteMapper.selectByReaderAndBookId(reader.getId(), bookId) != null;
        boolean canReview = hasBorrowedBook(reader.getId(), bookId);
        int availableCopies = inventory == null || inventory.getAvailableCopies() == null ? 0 : inventory.getAvailableCopies();
        int totalCopies = inventory == null || inventory.getTotalCopies() == null ? 0 : inventory.getTotalCopies();

        return ReaderBookDetailVo.builder()
                .bookId(book.getId())
                .title(book.getTitle())
                .author(book.getAuthor())
                .isbn(book.getIsbn())
                .publisher(book.getPublisher())
                .description(book.getDescription())
                .thumbnailUrl(book.getThumbnailUrl())
                .publishedDate(book.getPublishedDate())
                .categoryName(book.getCategory() == null ? null : book.getCategory().getName())
                .totalCopies(totalCopies)
                .availableCopies(availableCopies)
                .shelfStatus(book.getShelfStatus() == null ? null : book.getShelfStatus().name())
                .favorite(favorite)
                .averageRating(calculateAverageRating(reviews))
                .reviewCount(reviews.size())
                .canBorrow(availableCopies > 0)
                .canReserve(availableCopies <= 0)
                .canReview(canReview)
                .reviews(reviews.stream().map(review -> toReviewVo(review, reader.getId())).toList())
                .build();
    }

    @Override
    public ReaderFavoriteToggleVo toggleFavorite(String authorizationHeader, Long bookId) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("BOOK_SEARCH", "BOOK_VIEW")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);
        Book book = requireBook(bookId);

        BookFavorite favorite = bookFavoriteMapper.selectByReaderAndBookId(reader.getId(), bookId);
        if (favorite != null) {
            bookFavoriteMapper.deleteById(favorite.getId());
            return ReaderFavoriteToggleVo.builder()
                    .bookId(book.getId())
                    .favorite(false)
                    .message("Removed from favorites")
                    .build();
        }

        BookFavorite newFavorite = new BookFavorite();
        newFavorite.setReader(reader);
        newFavorite.setBook(book);
        bookFavoriteMapper.insert(newFavorite);
        return ReaderFavoriteToggleVo.builder()
                .bookId(book.getId())
                .favorite(true)
                .message("Added to favorites")
                .build();
    }

    @Override
    public ReaderBorrowRequestVo submitBorrowRequest(String authorizationHeader, ReaderBorrowRequestCreateRequest request) {
        permissionScopeSupport.requirePermission(authorizationHeader, RoleType.READER, "BORROW_REQUEST");
        User reader = currentUserSupport.requireUser(authorizationHeader);

        Book book = requireBook(request.getBookId());

        Inventory inventory = inventoryMapper.selectByBookId(book.getId());
        if (inventory == null || inventory.getAvailableCopies() == null || inventory.getAvailableCopies() <= 0) {
            throw new BusinessException(400, "book is currently unavailable for borrowing");
        }

        int borrowLimit = systemConfigSupport.getBorrowLimit();
        long activeBorrowCount = borrowRecordMapper.selectAll().stream()
                .filter(record -> record.getReader() != null && reader.getId().equals(record.getReader().getId()))
                .filter(record -> record.getStatus() == com.team.lms.common.enums.BorrowRecordStatus.BORROWED
                        || record.getStatus() == com.team.lms.common.enums.BorrowRecordStatus.RETURN_PENDING)
                .count();
        long pendingBorrowRequestCount = borrowRequestMapper.selectByReaderId(reader.getId()).stream()
                .filter(item -> item.getStatus() == BorrowRequestStatus.PENDING)
                .count();
        if (activeBorrowCount + pendingBorrowRequestCount >= borrowLimit) {
            throw new BusinessException(400, "borrow limit reached");
        }

        BorrowRequest borrowRequest = new BorrowRequest();
        borrowRequest.setReader(reader);
        borrowRequest.setBook(book);
        borrowRequest.setStatus(BorrowRequestStatus.PENDING);
        borrowRequest.setRequestNote(request.getRequestNote());
        borrowRequestMapper.insert(borrowRequest);

        return ReaderBorrowRequestVo.builder()
                .requestId(borrowRequest.getId())
                .bookId(book.getId())
                .bookTitle(book.getTitle())
                .status(borrowRequest.getStatus().name())
                .message("borrow request submitted and waiting for librarian approval")
                .build();
    }

    @Override
    public ReaderBookReviewVo submitReview(String authorizationHeader, Long bookId, ReaderBookReviewCreateRequest request) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("BOOK_SEARCH", "BOOK_VIEW")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);
        Book book = requireBook(bookId);

        if (!hasBorrowedBook(reader.getId(), bookId)) {
            throw new BusinessException(400, "only readers who have borrowed this book can review it");
        }

        BookReview review = new BookReview();
        review.setReader(reader);
        review.setBook(book);
        review.setRatingScore(request.getRatingScore());
        review.setReviewContent(request.getReviewContent().trim());
        review.setCreatedAt(java.time.LocalDateTime.now());
        review.setUpdatedAt(java.time.LocalDateTime.now());
        bookReviewMapper.insert(review);
        return toReviewVo(review, reader.getId());
    }

    @Override
    public List<ReaderBorrowRecordVo> listBorrowRecords(String authorizationHeader) {
        permissionScopeSupport.requireAnyPermission(
                authorizationHeader,
                RoleType.READER,
                List.of("RETURN_REQUEST", "BORROW_REQUEST", "BOOK_VIEW", "BOOK_SEARCH")
        );
        User reader = currentUserSupport.requireUser(authorizationHeader);

        Map<Long, Fine> fineByRecordId = fineMapper.selectAll().stream()
                .filter(fine -> fine.getBorrowRecord() != null && fine.getBorrowRecord().getId() != null)
                .collect(Collectors.toMap(
                        fine -> fine.getBorrowRecord().getId(),
                        Function.identity(),
                        (left, right) -> left
                ));

        return borrowRecordMapper.selectByReaderId(reader.getId()).stream()
                .map(record -> toBorrowRecordVo(record, fineByRecordId.get(record.getId()), null))
                .toList();
    }

    @Override
    public ReaderBorrowRecordVo submitReturnRequest(String authorizationHeader, Long recordId) {
        permissionScopeSupport.requirePermission(authorizationHeader, RoleType.READER, "RETURN_REQUEST");
        User reader = currentUserSupport.requireUser(authorizationHeader);

        BorrowRecord record = borrowRecordMapper.selectById(recordId);
        if (record == null) {
            throw new BusinessException(404, "borrow record not found");
        }
        if (record.getReader() == null || !reader.getId().equals(record.getReader().getId())) {
            throw new BusinessException(403, "cannot submit return request for another reader");
        }
        if (record.getStatus() == BorrowRecordStatus.RETURNED) {
            throw new BusinessException(400, "book has already been returned");
        }
        if (record.getStatus() == BorrowRecordStatus.RETURN_PENDING) {
            throw new BusinessException(400, "return request is already pending");
        }
        if (record.getStatus() != BorrowRecordStatus.BORROWED && record.getStatus() != BorrowRecordStatus.OVERDUE) {
            throw new BusinessException(400, "current borrow record cannot submit a return request");
        }

        record.setStatus(BorrowRecordStatus.RETURN_PENDING);
        borrowRecordMapper.update(record);

        Fine fine = findFineByRecordId(record.getId());
        return toBorrowRecordVo(record, fine, "return request submitted and waiting for librarian processing");
    }

    @Override
    public List<ReaderReservationVo> listReservations(String authorizationHeader) {
        permissionScopeSupport.requirePermission(authorizationHeader, RoleType.READER, "RESERVATION");
        User reader = currentUserSupport.requireUser(authorizationHeader);

        return reservationMapper.selectByReaderId(reader.getId()).stream()
                .map(item -> toReservationVo(item, null))
                .toList();
    }

    @Override
    public ReaderReservationVo createReservation(String authorizationHeader, Long bookId) {
        permissionScopeSupport.requirePermission(authorizationHeader, RoleType.READER, "RESERVATION");
        User reader = currentUserSupport.requireUser(authorizationHeader);

        if (bookId == null) {
            throw new BusinessException(400, "bookId is required");
        }

        Book book = bookMapper.selectById(bookId);
        if (book == null) {
            throw new BusinessException(404, "book not found");
        }
        Inventory inventory = inventoryMapper.selectByBookId(bookId);
        if (inventory != null && inventory.getAvailableCopies() != null && inventory.getAvailableCopies() > 0) {
            throw new BusinessException(400, "当前有库存，请直接借阅");
        }

        Reservation existingPendingReservation = reservationMapper.selectPendingByReaderAndBookId(reader.getId(), bookId);
        if (existingPendingReservation != null) {
            throw new BusinessException(400, "不能重复预约同一本待处理图书");
        }

        boolean hasActiveBorrow = borrowRecordMapper.selectByReaderId(reader.getId()).stream()
                .anyMatch(record -> record.getBook() != null
                        && bookId.equals(record.getBook().getId())
                        && (record.getStatus() == BorrowRecordStatus.BORROWED
                        || record.getStatus() == BorrowRecordStatus.RETURN_PENDING
                        || record.getStatus() == BorrowRecordStatus.OVERDUE));
        if (hasActiveBorrow) {
            throw new BusinessException(400, "you already have an active borrow for this book");
        }

        int nextQueueNo = reservationMapper.selectPendingByBookId(bookId).stream()
                .map(Reservation::getQueueNo)
                .filter(item -> item != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;

        Reservation reservation = new Reservation();
        reservation.setReader(reader);
        reservation.setBook(book);
        reservation.setStatus(ReservationStatus.PENDING);
        reservation.setQueueNo(nextQueueNo);
        reservationMapper.insert(reservation);
        Reservation savedReservation = reservationMapper.selectById(reservation.getId());

        return toReservationVo(savedReservation == null ? reservation : savedReservation, "预约成功");
    }

    private Fine findFineByRecordId(Long recordId) {
        return fineMapper.selectAll().stream()
                .filter(fine -> fine.getBorrowRecord() != null && recordId.equals(fine.getBorrowRecord().getId()))
                .findFirst()
                .orElse(null);
    }

    private Book requireBook(Long bookId) {
        Book book = bookMapper.selectById(bookId);
        if (book == null) {
            throw new BusinessException(404, "book not found");
        }
        return book;
    }

    private boolean hasBorrowedBook(Long readerId, Long bookId) {
        return borrowRecordMapper.selectByReaderId(readerId).stream()
                .anyMatch(record -> record.getBook() != null && bookId.equals(record.getBook().getId()));
    }

    private List<ReaderBookVo> toBookVoList(User reader, List<Book> books) {
        List<BookFavorite> favorites = bookFavoriteMapper.selectByReaderId(reader.getId());
        Map<Long, Boolean> favoriteBookIds = favorites.stream()
                .filter(item -> item.getBook() != null && item.getBook().getId() != null)
                .collect(Collectors.toMap(item -> item.getBook().getId(), item -> Boolean.TRUE, (left, right) -> left, HashMap::new));
        Map<Long, Double> averageRatingByBookId = buildAverageRatingByBookId();

        List<ReaderBookVo> result = new ArrayList<>();
        for (Book book : books) {
            Inventory inventory = inventoryMapper.selectByBookId(book.getId());
            result.add(ReaderBookVo.builder()
                    .bookId(book.getId())
                    .title(book.getTitle())
                    .author(book.getAuthor())
                    .categoryName(book.getCategory() == null ? null : book.getCategory().getName())
                    .thumbnailUrl(book.getThumbnailUrl())
                    .availableCopies(inventory == null ? 0 : inventory.getAvailableCopies())
                    .shelfStatus(book.getShelfStatus() == null ? null : book.getShelfStatus().name())
                    .favorite(Boolean.TRUE.equals(favoriteBookIds.get(book.getId())))
                    .averageRating(averageRatingByBookId.getOrDefault(book.getId(), 0.0))
                    .build());
        }
        return result;
    }

    private Map<Long, Double> buildAverageRatingByBookId() {
        Map<Long, List<BookReview>> reviewsByBookId = bookReviewMapper.selectAll().stream()
                .filter(item -> item.getBook() != null && item.getBook().getId() != null)
                .collect(Collectors.groupingBy(item -> item.getBook().getId()));

        Map<Long, Double> averages = new HashMap<>();
        reviewsByBookId.forEach((bookId, reviews) -> averages.put(bookId, calculateAverageRating(reviews)));
        return averages;
    }

    private double calculateAverageRating(List<BookReview> reviews) {
        if (reviews == null || reviews.isEmpty()) {
            return 0.0;
        }
        BigDecimal total = reviews.stream()
                .map(BookReview::getRatingScore)
                .map(score -> BigDecimal.valueOf(score == null ? 0 : score))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.divide(BigDecimal.valueOf(reviews.size()), 1, RoundingMode.HALF_UP).doubleValue();
    }

    private ReaderBookReviewVo toReviewVo(BookReview review, Long currentReaderId) {
        return ReaderBookReviewVo.builder()
                .reviewId(review.getId())
                .readerUsername(review.getReader() == null ? null : review.getReader().getUsername())
                .ratingScore(review.getRatingScore())
                .reviewContent(review.getReviewContent())
                .createdAt(review.getCreatedAt() == null ? null : review.getCreatedAt().toString())
                .mine(review.getReader() != null && currentReaderId.equals(review.getReader().getId()))
                .build();
    }

    private ReaderBorrowRecordVo toBorrowRecordVo(BorrowRecord record, Fine fine, String message) {
        BorrowRecordStatus displayStatus = resolveDisplayStatus(record);
        long overdueDays = calculateOverdueDays(record);
        BigDecimal fineAmount = fine == null ? estimateFine(record, overdueDays) : fine.getAmount();

        return ReaderBorrowRecordVo.builder()
                .recordId(record.getId())
                .bookId(record.getBook() == null ? null : record.getBook().getId())
                .bookTitle(record.getBook() == null ? null : record.getBook().getTitle())
                .copyBarcode(record.getBookCopy() == null ? null : record.getBookCopy().getBarcode())
                .status(displayStatus.name())
                .borrowDate(record.getBorrowDate() == null ? null : record.getBorrowDate().toString())
                .dueDate(record.getDueDate() == null ? null : record.getDueDate().toString())
                .returnDate(record.getReturnDate() == null ? null : record.getReturnDate().toString())
                .fineAmount(fineAmount)
                .overdueDays(overdueDays)
                .canRequestReturn(displayStatus == BorrowRecordStatus.BORROWED || displayStatus == BorrowRecordStatus.OVERDUE)
                .message(message)
                .build();
    }

    private ReaderReservationVo toReservationVo(Reservation reservation, String message) {
        return ReaderReservationVo.builder()
                .reservationId(reservation.getId())
                .bookId(reservation.getBook() == null ? null : reservation.getBook().getId())
                .bookTitle(reservation.getBook() == null ? null : reservation.getBook().getTitle())
                .status(reservation.getStatus() == null ? null : reservation.getStatus().name())
                .queueNo(reservation.getQueueNo())
                .createdAt(reservation.getCreatedAt() == null ? null : reservation.getCreatedAt().toString())
                .message(message)
                .build();
    }

    private BorrowRecordStatus resolveDisplayStatus(BorrowRecord record) {
        if (record.getStatus() == BorrowRecordStatus.BORROWED
                && record.getDueDate() != null
                && LocalDate.now().isAfter(record.getDueDate())) {
            return BorrowRecordStatus.OVERDUE;
        }
        return record.getStatus();
    }

    private long calculateOverdueDays(BorrowRecord record) {
        if (record.getDueDate() == null || !LocalDate.now().isAfter(record.getDueDate())) {
            return 0;
        }
        if (record.getStatus() == BorrowRecordStatus.RETURNED && record.getReturnDate() != null) {
            return Math.max(0, ChronoUnit.DAYS.between(record.getDueDate(), record.getReturnDate()));
        }
        return Math.max(0, ChronoUnit.DAYS.between(record.getDueDate(), LocalDate.now()));
    }

    private BigDecimal estimateFine(BorrowRecord record, long overdueDays) {
        if (overdueDays <= 0 || record.getStatus() == BorrowRecordStatus.RETURN_PENDING) {
            return BigDecimal.ZERO;
        }
        return systemConfigSupport.getOverdueFinePerDay().multiply(BigDecimal.valueOf(overdueDays));
    }
}
