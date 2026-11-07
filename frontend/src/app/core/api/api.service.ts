import { HttpClient, HttpContext, HttpContextToken, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ConfigService } from '../config/config.service';

/** Quando true, o interceptor de erros não exibe a mensagem automaticamente. */
export const SILENT_ERRORS = new HttpContextToken<boolean>(() => false);

type ParamValue = string | number | boolean | null | undefined;

/** Cliente HTTP da API REST (o token é anexado pelo authInterceptor). */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);
  private readonly config = inject(ConfigService);

  get<T>(path: string, params?: Record<string, ParamValue>, silent = false): Observable<T> {
    return this.http.get<T>(this.url(path), { params: this.params(params), context: this.ctx(silent) });
  }

  post<T>(path: string, body: unknown = {}, silent = false): Observable<T> {
    return this.http.post<T>(this.url(path), body, { context: this.ctx(silent) });
  }

  put<T>(path: string, body: unknown): Observable<T> {
    return this.http.put<T>(this.url(path), body);
  }

  patch<T>(path: string, body: unknown): Observable<T> {
    return this.http.patch<T>(this.url(path), body);
  }

  delete<T>(path: string): Observable<T> {
    return this.http.delete<T>(this.url(path));
  }

  private url(path: string): string {
    return `${this.config.apiUrl}${path.startsWith('/') ? path : '/' + path}`;
  }

  private params(values?: Record<string, ParamValue>): HttpParams {
    let params = new HttpParams();
    Object.entries(values ?? {}).forEach(([key, value]) => {
      if (value !== null && value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    });
    return params;
  }

  private ctx(silent: boolean): HttpContext {
    return new HttpContext().set(SILENT_ERRORS, silent);
  }
}
