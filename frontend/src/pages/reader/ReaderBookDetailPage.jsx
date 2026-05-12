import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { readerApi } from "../../api/client";

const EMPTY_REVIEW_FORM = {
  ratingScore: "5",
  reviewContent: "",
};

export function ReaderBookDetailPage({ workspace }) {
  const { bookId } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState(null);
  const [reviewForm, setReviewForm] = useState(EMPTY_REVIEW_FORM);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function loadDetail() {
    setLoading(true);
    setError("");
    try {
      const result = await readerApi.getBookDetail(workspace?.token, bookId);
      setDetail(result);
    } catch (requestError) {
      setError(requestError.message || "Failed to load book detail");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadDetail();
  }, [workspace?.token, bookId]);

  async function handleToggleFavorite() {
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const result = await readerApi.toggleFavorite(workspace?.token, bookId);
      setMessage(result.message || "Favorite status updated.");
      await loadDetail();
    } catch (requestError) {
      setError(requestError.message || "Failed to update favorite");
    } finally {
      setSaving(false);
    }
  }

  async function handleBorrow() {
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const result = await readerApi.submitBorrowRequest(workspace?.token, {
        bookId: Number(bookId),
        requestNote: `Borrow request submitted by ${workspace?.username || "reader"}`,
      });
      setMessage(`Borrow request #${result.requestId} submitted successfully.`);
      await loadDetail();
    } catch (requestError) {
      setError(requestError.message || "Failed to submit borrow request");
    } finally {
      setSaving(false);
    }
  }

  async function handleReserve() {
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const result = await readerApi.createReservation(workspace?.token, { bookId: Number(bookId) });
      setMessage(result.message || "预约成功");
      await loadDetail();
    } catch (requestError) {
      setError(requestError.message || "Failed to create reservation");
    } finally {
      setSaving(false);
    }
  }

  async function handleSubmitReview(event) {
    event.preventDefault();
    setSaving(true);
    setMessage("");
    setError("");
    try {
      await readerApi.submitReview(workspace?.token, bookId, {
        ratingScore: Number(reviewForm.ratingScore),
        reviewContent: reviewForm.reviewContent,
      });
      setMessage("Review submitted successfully.");
      setReviewForm(EMPTY_REVIEW_FORM);
      await loadDetail();
    } catch (requestError) {
      setError(requestError.message || "Failed to submit review");
    } finally {
      setSaving(false);
    }
  }

  if (loading && !detail) {
    return (
      <section className="page-card">
        <p className="page-note">Loading book detail...</p>
      </section>
    );
  }

  if (!detail) {
    return (
      <section className="page-card">
        <div className="inline-actions">
          <button className="secondary-button" type="button" onClick={() => navigate("/reader/books")}>
            Back To Books
          </button>
        </div>
        <p className="page-note">{error || "Book detail is unavailable."}</p>
      </section>
    );
  }

  return (
    <div className="page-stack">
      <section className="page-card">
        <div className="section-head compact">
          <div>
            <span className="eyebrow">Reader</span>
            <h2 className="section-title">{detail.title}</h2>
          </div>
          <Link className="secondary-button detail-link-button" to="/reader/books">
            Back To Books
          </Link>
        </div>

        <div className="detail-grid">
          <div className="detail-hero">
            {detail.thumbnailUrl ? (
              <img src={detail.thumbnailUrl} alt={detail.title} className="cover-detail" />
            ) : (
              <div className="cover-placeholder-detail">No Cover</div>
            )}
            <div>
              <div className="detail-meta">
                <strong>{detail.author}</strong>
                <span>{detail.categoryName || "Uncategorized"}</span>
                <span>ISBN: {detail.isbn || "-"}</span>
                <span>Publisher: {detail.publisher || "-"}</span>
                {detail.publishedDate ? <span>Published: {detail.publishedDate}</span> : null}
              </div>
              <p className="page-note">{detail.description || "No description available."}</p>
            </div>
          </div>

          <div className="detail-side">
            <div className="monitor-card">
              <small>Availability</small>
              <strong>{detail.availableCopies ?? 0} / {detail.totalCopies ?? 0}</strong>
            </div>
            <div className="monitor-card">
              <small>Average Rating</small>
              <strong>{detail.averageRating ? detail.averageRating.toFixed(1) : "No ratings"}</strong>
            </div>
            <div className="monitor-card">
              <small>Favorite Status</small>
              <strong>{detail.favorite ? "Favorited" : "Not Favorited"}</strong>
            </div>
          </div>
        </div>

        <div className="inline-actions">
          <button className="secondary-button" type="button" onClick={handleToggleFavorite} disabled={saving}>
            {detail.favorite ? "Remove Favorite" : "Add To Favorites"}
          </button>
          <button className="primary-button" type="button" onClick={handleBorrow} disabled={saving || !detail.canBorrow}>
            Request Borrow
          </button>
          <button className="primary-button" type="button" onClick={handleReserve} disabled={saving || !detail.canReserve}>
            Reserve Unavailable Book
          </button>
        </div>
        {message ? <p className="page-note">{message}</p> : null}
        {error ? <p className="page-note">{error}</p> : null}
      </section>

      <section className="page-card split-grid">
        <div>
          <h3 className="section-title">Reviews</h3>
          <div className="review-list">
            {detail.reviews?.length ? (
              detail.reviews.map((review) => (
                <article key={review.reviewId} className={`review-card${review.mine ? " mine" : ""}`}>
                  <div className="review-head">
                    <div>
                      <strong>{review.readerUsername}</strong>
                      {review.mine ? <span className="badge">Your Review</span> : null}
                    </div>
                    <span className="stars">{renderStars(review.ratingScore)}</span>
                  </div>
                  <p>{review.reviewContent}</p>
                  <small>{formatDate(review.createdAt)}</small>
                </article>
              ))
            ) : (
              <p className="page-note">No reviews yet.</p>
            )}
          </div>
        </div>

        <div>
          <h3 className="section-title">Write A Review</h3>
          {detail.canReview ? (
            <form className="auth-form" onSubmit={handleSubmitReview}>
              <select
                value={reviewForm.ratingScore}
                onChange={(event) => setReviewForm((prev) => ({ ...prev, ratingScore: event.target.value }))}
              >
                <option value="5">5 - Excellent</option>
                <option value="4">4 - Good</option>
                <option value="3">3 - Average</option>
                <option value="2">2 - Poor</option>
                <option value="1">1 - Bad</option>
              </select>
              <textarea
                placeholder="Share your reading experience"
                value={reviewForm.reviewContent}
                onChange={(event) => setReviewForm((prev) => ({ ...prev, reviewContent: event.target.value }))}
              />
              <button className="primary-button" type="submit" disabled={saving}>
                {saving ? "Submitting..." : "Submit Review"}
              </button>
            </form>
          ) : (
            <p className="page-note">Only readers who have borrowed this book can submit a rating and review.</p>
          )}
        </div>
      </section>
    </div>
  );
}

function renderStars(score) {
  const count = Math.max(0, Math.min(5, Number(score || 0)));
  return `${"★".repeat(count)}${"☆".repeat(Math.max(0, 5 - count))}`;
}

function formatDate(dateStr) {
  if (!dateStr) return "-";
  const date = new Date(dateStr);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hours = String(date.getHours()).padStart(2, "0");
  const minutes = String(date.getMinutes()).padStart(2, "0");
  return `${year}-${month}-${day} ${hours}:${minutes}`;
}
