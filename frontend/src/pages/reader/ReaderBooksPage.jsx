import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { readerApi } from "../../api/client";
import { StatCard } from "../../components/StatCard";

export function ReaderBooksPage({ workspace }) {
  const navigate = useNavigate();
  const permissionsLoaded = Array.isArray(workspace?.permissions);
  const permissions = permissionsLoaded ? workspace.permissions : [];
  const canViewBooks = !permissionsLoaded || permissions.includes("BOOK_VIEW") || permissions.includes("BOOK_SEARCH");
  const canSearch = !permissionsLoaded || permissions.includes("BOOK_SEARCH");
  const canBorrow = !permissionsLoaded || permissions.includes("BORROW_REQUEST");
  const canReserve = !permissionsLoaded || permissions.includes("RESERVATION");

  const [viewMode, setViewMode] = useState("ALL");
  const [keyword, setKeyword] = useState("");
  const [appliedKeyword, setAppliedKeyword] = useState("");
  const [books, setBooks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [submittingId, setSubmittingId] = useState(null);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function loadBooks(nextViewMode = viewMode) {
    if (!canViewBooks) {
      setBooks([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError("");
    try {
      const result =
        nextViewMode === "FAVORITES"
          ? await readerApi.listFavoriteBooks(workspace?.token)
          : await readerApi.listBooks(workspace?.token);
      setBooks(result || []);
    } catch (requestError) {
      setError(requestError.message || "Failed to load books");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadBooks(viewMode);
  }, [workspace?.token, canViewBooks, viewMode]);

  const visibleBooks = useMemo(() => {
    const normalized = appliedKeyword.trim().toLowerCase();
    if (!normalized) {
      return books;
    }
    return books.filter((book) =>
      [book.title, book.author, book.categoryName]
        .filter(Boolean)
        .some((value) => value.toLowerCase().includes(normalized))
    );
  }, [books, appliedKeyword]);

  async function handleSearch() {
    if (!canSearch) {
      setError("Current reader role cannot search books.");
      return;
    }
    setAppliedKeyword(keyword.trim());
  }

  async function handleBorrow(bookId) {
    if (!canBorrow) {
      setError("Current reader role cannot submit borrow requests.");
      return;
    }

    setSubmittingId(bookId);
    setMessage("");
    setError("");

    try {
      const result = await readerApi.submitBorrowRequest(workspace?.token, {
        bookId,
        requestNote: `Borrow request submitted by ${workspace?.username || "reader"}`,
      });
      setMessage(`Borrow request #${result.requestId} submitted successfully.`);
      await loadBooks(viewMode);
    } catch (requestError) {
      setError(requestError.message || "Failed to submit borrow request");
    } finally {
      setSubmittingId(null);
    }
  }

  async function handleReserve(bookId) {
    if (!canReserve) {
      setError("Current reader role cannot create reservations.");
      return;
    }

    setSubmittingId(bookId);
    setMessage("");
    setError("");

    try {
      const result = await readerApi.createReservation(workspace?.token, { bookId });
      setMessage(result.message || "预约成功");
      await loadBooks(viewMode);
    } catch (requestError) {
      setError(requestError.message || "Failed to create reservation");
    } finally {
      setSubmittingId(null);
    }
  }

  async function handleToggleFavorite(bookId) {
    setSubmittingId(bookId);
    setMessage("");
    setError("");
    try {
      const result = await readerApi.toggleFavorite(workspace?.token, bookId);
      setMessage(result.message || "Favorite status updated.");
      await loadBooks(viewMode);
    } catch (requestError) {
      setError(requestError.message || "Failed to update favorite");
    } finally {
      setSubmittingId(null);
    }
  }

  const stats = useMemo(
    () => ({
      total: visibleBooks.length,
      favorites: visibleBooks.filter((book) => book.favorite).length,
      lowStock: visibleBooks.filter((book) => (book.availableCopies || 0) > 0 && (book.availableCopies || 0) <= 2).length,
    }),
    [visibleBooks]
  );

  return (
    <div className="page-stack">
      <section className="page-card">
        <div className="section-head compact">
          <div>
            <span className="eyebrow">Reader</span>
            <h2 className="section-title">Books</h2>
          </div>
        </div>

        <div className="stats-grid">
          <StatCard label="Visible Books" value={stats.total} />
          <StatCard label="Favorites" value={stats.favorites} />
          <StatCard label="Low Stock" value={stats.lowStock} />
        </div>
      </section>

      <section className="page-card">
        {!canViewBooks ? (
          <p className="page-note">Current reader role does not have permission to view the book catalog.</p>
        ) : (
          <>
            <div className="inline-actions">
              <button
                className={viewMode === "ALL" ? "primary-button" : "secondary-button"}
                type="button"
                onClick={() => setViewMode("ALL")}
              >
                All Books
              </button>
              <button
                className={viewMode === "FAVORITES" ? "primary-button" : "secondary-button"}
                type="button"
                onClick={() => setViewMode("FAVORITES")}
              >
                Favorites List
              </button>
            </div>
            {canSearch ? (
              <div className="toolbar">
                <input
                  className="search-input"
                  placeholder="Search by title, author, or category"
                  value={keyword}
                  onChange={(event) => setKeyword(event.target.value)}
                />
                <button className="primary-button" type="button" onClick={handleSearch} disabled={loading}>
                  {loading ? "Loading..." : "Search"}
                </button>
              </div>
            ) : null}
            {message ? <p className="page-note">{message}</p> : null}
            {error ? <p className="page-note">{error}</p> : null}
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Cover</th>
                    <th>ID</th>
                    <th>Title</th>
                    <th>Author</th>
                    <th>Category</th>
                    <th>Available</th>
                    <th>Favorite</th>
                    <th>Rating</th>
                    <th>Action</th>
                  </tr>
                </thead>
                <tbody>
                  {visibleBooks.map((book) => (
                    <tr key={book.bookId}>
                      <td>
                        {book.thumbnailUrl ? (
                          <img src={book.thumbnailUrl} alt={book.title} className="cover-thumb-table" />
                        ) : (
                          <span className="cover-placeholder">-</span>
                        )}
                      </td>
                      <td>{book.bookId}</td>
                      <td>{book.title}</td>
                      <td>{book.author}</td>
                      <td>{book.categoryName || "Uncategorized"}</td>
                      <td>{book.availableCopies ?? 0}</td>
                      <td>{book.favorite ? "Yes" : "No"}</td>
                      <td>{book.averageRating ? book.averageRating.toFixed(1) : "-"}</td>
                      <td>
                        <div className="table-actions">
                          <button className="secondary-button" type="button" onClick={() => navigate(`/reader/books/${book.bookId}`)}>
                            View Details
                          </button>
                          <button
                            className="secondary-button"
                            type="button"
                            disabled={submittingId === book.bookId}
                            onClick={() => handleToggleFavorite(book.bookId)}
                          >
                            {book.favorite ? "Unfavorite" : "Favorite"}
                          </button>
                          {canBorrow ? (
                            <button
                              className="primary-button"
                              type="button"
                              disabled={submittingId === book.bookId || (book.availableCopies ?? 0) <= 0}
                              onClick={() => handleBorrow(book.bookId)}
                            >
                              {submittingId === book.bookId ? "Submitting..." : "Request Borrow"}
                            </button>
                          ) : null}
                          {canReserve && (book.availableCopies ?? 0) <= 0 ? (
                            <button
                              className="primary-button"
                              type="button"
                              disabled={submittingId === book.bookId}
                              onClick={() => handleReserve(book.bookId)}
                            >
                              {submittingId === book.bookId ? "Submitting..." : "预约"}
                            </button>
                          ) : null}
                        </div>
                      </td>
                    </tr>
                  ))}
                  {!loading && visibleBooks.length === 0 ? (
                    <tr>
                      <td colSpan="9">No books found</td>
                    </tr>
                  ) : null}
                </tbody>
              </table>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
