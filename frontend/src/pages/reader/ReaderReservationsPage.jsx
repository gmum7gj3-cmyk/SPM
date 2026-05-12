import { useEffect, useMemo, useState } from "react";
import { readerApi } from "../../api/client";
import { StatCard } from "../../components/StatCard";

export function ReaderReservationsPage({ workspace }) {
  const permissionsLoaded = Array.isArray(workspace?.permissions);
  const permissions = permissionsLoaded ? workspace.permissions : [];
  const canReserve = !permissionsLoaded || permissions.includes("RESERVATION");

  const [reservations, setReservations] = useState([]);
  const [books, setBooks] = useState([]);
  const [bookId, setBookId] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");

  async function loadData() {
    if (!canReserve) {
      setReservations([]);
      setBooks([]);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError("");
    try {
      const [reservationList, bookList] = await Promise.all([
        readerApi.listReservations(workspace?.token),
        readerApi.listBooks(workspace?.token),
      ]);
      setReservations(reservationList || []);
      setBooks((bookList || []).filter((book) => Number(book.availableCopies || 0) <= 0));
    } catch (requestError) {
      setError(requestError.message || "Failed to load reservation data");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadData();
  }, [workspace?.token, canReserve]);

  async function handleCreateReservation() {
    if (!canReserve) {
      setError("Current reader role cannot create reservations.");
      return;
    }
    if (!bookId) {
      setError("Please select a book first.");
      setMessage("");
      return;
    }

    setSaving(true);
    setMessage("");
    setError("");
    try {
      const result = await readerApi.createReservation(workspace?.token, { bookId: Number(bookId) });
      setMessage(result.message || "预约成功");
      setBookId("");
      await loadData();
    } catch (requestError) {
      setError(requestError.message || "Failed to create reservation");
    } finally {
      setSaving(false);
    }
  }

  const stats = useMemo(() => ({
    pending: reservations.filter((item) => item.status === "PENDING").length,
    fulfilled: reservations.filter((item) => item.status === "FULFILLED").length,
    queueItems: reservations.length,
  }), [reservations]);

  return (
    <div className="page-stack">
      <section className="page-card">
        <div className="section-head compact">
          <div>
            <span className="eyebrow">Reader</span>
            <h2 className="section-title">Reservation Queue</h2>
          </div>
        </div>

        <div className="stats-grid">
          <StatCard label="Pending" value={stats.pending} />
          <StatCard label="Fulfilled" value={stats.fulfilled} />
          <StatCard label="Queue Items" value={stats.queueItems} />
        </div>
      </section>

      <section className="page-card">
        {!canReserve ? (
          <p className="page-note">Current reader role does not have permission to manage reservations.</p>
        ) : (
          <>
            <div className="form-grid">
              <select value={bookId} onChange={(event) => setBookId(event.target.value)}>
                <option value="">Select an unavailable book to reserve</option>
                {books.map((book) => (
                  <option key={book.bookId} value={book.bookId}>
                    #{book.bookId} {book.title} ({book.availableCopies ?? 0} available)
                  </option>
                ))}
              </select>
              <button className="primary-button" type="button" onClick={handleCreateReservation} disabled={saving || loading}>
                {saving ? "Creating..." : "Create Reservation"}
              </button>
            </div>
            <div className="inline-actions">
              <button className="secondary-button" type="button" onClick={loadData} disabled={loading}>
                {loading ? "Loading..." : "Refresh"}
              </button>
            </div>
            <p className="page-note">Only unavailable books can be reserved through this feature.</p>
            {message ? <p className="page-note">{message}</p> : null}
            {error ? <p className="page-note">{error}</p> : null}
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Book</th>
                    <th>Status</th>
                    <th>Queue No</th>
                    <th>Created At</th>
                  </tr>
                </thead>
                <tbody>
                  {reservations.map((item) => (
                    <tr key={item.reservationId}>
                      <td>{item.reservationId}</td>
                      <td>{item.bookTitle}</td>
                      <td>{item.status}</td>
                      <td>{item.queueNo}</td>
                      <td>{item.createdAt || "-"}</td>
                    </tr>
                  ))}
                  {!loading && reservations.length === 0 ? (
                    <tr>
                      <td colSpan="5">No reservations.</td>
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
