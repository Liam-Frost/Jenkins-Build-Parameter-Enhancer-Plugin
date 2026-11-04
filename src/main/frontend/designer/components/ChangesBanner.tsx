import { t } from "../../common/i18n";
import type { Changes } from "../../common/types";

interface Props {
  changes: Changes;
  stale: string[];
  onPlaceNew: () => void;
  onRemoveStale: () => void;
}

/**
 * Pending differences between the saved layout and the job's current parameters. New parameters are never hidden
 * from users, so this is information plus two explicit actions, not a forced migration.
 */
export function ChangesBanner({ changes, stale, onPlaceNew, onRemoveStale }: Props) {
  const nothing = changes.added.length === 0 && changes.typeChanged.length === 0 && stale.length === 0;
  if (nothing) {
    return null;
  }
  return (
    <div className="bpe-banner" role="status">
      <div className="bpe-banner__body">
        <strong>{t("designer.changes.title")}</strong>
        {changes.added.length > 0 && (
          <p>
            {t("designer.changes.added")} {changes.added.map((n) => <code key={n} className="bpe-code">{n}</code>)}{" "}
            {t("designer.changes.added.hint")}
          </p>
        )}
        {changes.typeChanged.length > 0 && (
          <p>
            {t("designer.changes.typeChanged")} {changes.typeChanged.map((n) => <code key={n} className="bpe-code">{n}</code>)}{" "}
            {t("designer.changes.typeChanged.hint")}
          </p>
        )}
        {stale.length > 0 && (
          <p>
            {t("designer.changes.stale")} {stale.map((n) => <code key={n} className="bpe-code">{n}</code>)}{" "}
            {t("designer.changes.stale.hint")}
          </p>
        )}
      </div>
      <div className="bpe-banner__actions">
        {changes.added.length > 0 && (
          <button type="button" className="bpe-button" onClick={onPlaceNew}>
            {t("designer.changes.placeNew")}
          </button>
        )}
        {stale.length > 0 && (
          <button type="button" className="bpe-button" onClick={onRemoveStale}>
            {t("designer.changes.removeStale")}
          </button>
        )}
      </div>
    </div>
  );
}
