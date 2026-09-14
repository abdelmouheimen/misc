// Couche d'accès au backend du lab. Chaque appel attache une preuve DPoP fraîche
// et envoie les cookies (credentials:'include') — le refresh token vit dans un cookie
// HttpOnly que le navigateur renvoie automatiquement.

import { buildDPopProof } from './dpopService';

const BASE_URL = import.meta.env.VITE_BACKEND_URL ?? 'http://localhost:8099';

export interface AuthResponse {
  status: 'AUTHENTICATED' | 'NONE';
  reason?: string | null;
  accessToken?: string | null;
  refreshToken?: string | null;
}

async function postWithDPop(path: string, body?: unknown): Promise<AuthResponse> {
  const url = `${BASE_URL}${path}`;
  const proof = await buildDPopProof('POST', url);
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (proof) headers['DPoP'] = proof;

  const res = await fetch(url, {
    method: 'POST',
    headers,
    credentials: 'include',
    body: body ? JSON.stringify(body) : undefined,
  });
  return res.json();
}

export const connect = (uid: string) => postWithDPop('/login/connection', { uid });
export const refresh = () => postWithDPop('/login/refresh');

export async function fetchTokens(): Promise<unknown[]> {
  const res = await fetch(`${BASE_URL}/debug/tokens`);
  return res.json();
}

export async function reset(): Promise<void> {
  await fetch(`${BASE_URL}/debug/reset`, { method: 'POST' });
}
