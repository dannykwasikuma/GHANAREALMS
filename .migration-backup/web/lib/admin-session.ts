import { cookies } from 'next/headers';
import { verifyAdminSession, AdminSession } from './admin-auth';

export async function getAdminSession(): Promise<AdminSession | null> {
  const cookieStore = await cookies();
  const token = cookieStore.get('gr_admin_session')?.value;
  if (!token) return null;
  return verifyAdminSession(token);
}
