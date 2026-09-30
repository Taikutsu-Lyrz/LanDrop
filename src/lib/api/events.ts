import { listen } from "@tauri-apps/api/event";
import type {
  AppSettings,
  IncomingTransferOffer,
  PeerDevice,
  SendDraft,
  TransferSummary
} from "./types";

type Unlisten = () => void;

export const DEVICES_UPDATED_EVENT = "landrop://devices-updated";
export const SETTINGS_UPDATED_EVENT = "landrop://settings-updated";
export const INCOMING_TRANSFER_EVENT = "landrop://incoming-transfer";
export const SEND_DRAFT_UPDATED_EVENT = "landrop://send-draft-updated";
export const TRANSFERS_UPDATED_EVENT = "landrop://transfers-updated";

function isTauriRuntime() {
  return Boolean(
    typeof window !== "undefined" &&
      "__TAURI_INTERNALS__" in window &&
      (window as Window & { __TAURI_INTERNALS__?: unknown }).__TAURI_INTERNALS__
  );
}

async function listenIfAvailable<T>(
  event: string,
  handler: (payload: T) => void
): Promise<Unlisten> {
  if (!isTauriRuntime()) {
    return () => undefined;
  }

  return listen<T>(event, (received) => handler(received.payload));
}

export function listenDevicesUpdated(
  handler: (devices: PeerDevice[]) => void
): Promise<Unlisten> {
  return listenIfAvailable(DEVICES_UPDATED_EVENT, handler);
}

export function listenSettingsUpdated(
  handler: (settings: AppSettings) => void
): Promise<Unlisten> {
  return listenIfAvailable(SETTINGS_UPDATED_EVENT, handler);
}

export function listenIncomingTransfer(
  handler: (offer: IncomingTransferOffer | null) => void
): Promise<Unlisten> {
  return listenIfAvailable(INCOMING_TRANSFER_EVENT, handler);
}

export function listenSendDraftUpdated(
  handler: (draft: SendDraft | null) => void
): Promise<Unlisten> {
  return listenIfAvailable(SEND_DRAFT_UPDATED_EVENT, handler);
}

export function listenTransfersUpdated(
  handler: (transfers: TransferSummary[]) => void
): Promise<Unlisten> {
  return listenIfAvailable(TRANSFERS_UPDATED_EVENT, handler);
}
