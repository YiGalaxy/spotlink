-- =============================================================================
-- Releases goods left frozen by a bug that has since been fixed.
--
-- What happened: partial consumption of a listing's freeze closes the original
-- record and opens a new one for the remainder, and the listing kept pointing
-- at the closed one. Two consequences, both silent:
--
--   * the listing could not be accepted again — the freeze it named was settled
--   * withdrawing it raised a null pointer, which the caller saw as 系统繁忙,
--     and the remainder stayed frozen with no path to release it
--
-- The code is fixed (OrderService now moves the pointer and ListingService can
-- release what is left). This repairs the rows that were stranded while it was
-- broken.
--
-- What it considers stranded: a listing freeze that still says FROZEN and that
-- no open listing points at.
--
-- The condition is written that way round because the obvious one does not
-- work: a listing's freeze is created before the listing is inserted, so its
-- biz_id is null and joining the two on it matches nothing. That gap has since
-- been closed for new rows, but these predate it. Asking "which open listing
-- claims this reservation" needs no id and cannot match a healthy listing — an
-- open listing always points at its own live freeze.
--
-- Idempotent: a released freeze is no longer FROZEN, so a second run finds
-- nothing.
--
--   docker exec -i spotlink-mysql mysql --default-character-set=utf8mb4 \
--     -ubulk -pbulk_trade_2026 bulk_trade < scripts/repair-stuck-listing-freezes.sql
-- =============================================================================

SET NAMES utf8mb4;

-- The goods go back to available. Done as one statement against the notes so
-- the three quantities stay consistent — released and available move together
-- or not at all.
CREATE TEMPORARY TABLE tmp_stranded_freeze AS
SELECT f.id        AS freeze_id,
       f.entity_id AS note_id,
       f.quantity  AS quantity
  FROM t_freeze_record f
 WHERE f.status = 'FROZEN'
   AND f.biz_type = 'LISTING'
   AND NOT EXISTS (
       SELECT 1 FROM t_listing l
        WHERE l.deleted = 0
          AND l.freeze_id = f.id
          AND l.status IN ('OPEN', 'PARTIALLY_FILLED'));

SELECT COUNT(*) AS stranded_freezes,
       COALESCE(SUM(quantity), 0) AS stranded_quantity
  FROM tmp_stranded_freeze;

UPDATE t_inventory_note n
  JOIN (SELECT note_id, SUM(quantity) AS qty
          FROM tmp_stranded_freeze GROUP BY note_id) s ON s.note_id = n.id
   SET n.frozen_quantity    = n.frozen_quantity - s.qty,
       n.available_quantity = n.available_quantity + s.qty,
       -- Restates the status from the arithmetic rather than from a guess, the
       -- same way FreezeService derives it.
       n.status = CASE WHEN n.frozen_quantity - s.qty = 0 THEN 2
                       WHEN n.available_quantity + s.qty = 0 THEN 4
                       ELSE 3 END
 WHERE n.deleted = 0;

UPDATE t_freeze_record f
  JOIN tmp_stranded_freeze s ON s.freeze_id = f.id
   SET f.status = 'RELEASED',
       f.released_at = NOW(6),
       f.reason = CONCAT(COALESCE(f.reason, ''), '（修复：部分成交后指针未跟随，残留冻结）');

-- -----------------------------------------------------------------------------
-- Offers that just lost their reservation, or never had one, are closed.
--
-- An open listing whose goods are not set aside is an invitation to accept
-- something that is not there. Closing it is the honest end state: the offer
-- cannot be honoured, and leaving it on the board makes the market look deeper
-- than it is.
--
-- This is the part the first version of this script got wrong. Releasing a
-- stranded freeze sounds safe, and the quantities stayed consistent so nothing
-- complained — but the listings whose pointer was broken were relying on
-- exactly those records, and releasing them quietly turned a repairable offer
-- into an unbacked one. Consistency is not the same as correctness.
-- -----------------------------------------------------------------------------
UPDATE t_listing l
   SET l.status = 'CLOSED',
       l.updated_at = NOW(6)
 WHERE l.deleted = 0
   AND l.side = 'SELL'
   AND l.status IN ('OPEN', 'PARTIALLY_FILLED')
   AND NOT EXISTS (
       SELECT 1 FROM t_freeze_record f
        WHERE f.id = l.freeze_id AND f.status = 'FROZEN');

DROP TEMPORARY TABLE tmp_stranded_freeze;

-- Nothing should remain. A non-zero count here means a shape this script does
-- not cover, and it is worth looking at rather than re-running.
SELECT COUNT(*) AS still_stranded
  FROM t_freeze_record f
 WHERE f.status = 'FROZEN'
   AND f.biz_type = 'LISTING'
   AND NOT EXISTS (
       SELECT 1 FROM t_listing l
        WHERE l.deleted = 0
          AND l.freeze_id = f.id
          AND l.status IN ('OPEN', 'PARTIALLY_FILLED'));

SELECT COUNT(*) AS quantity_invariant_violations
  FROM t_inventory_note
 WHERE deleted = 0 AND available_quantity + frozen_quantity <> total_quantity;
